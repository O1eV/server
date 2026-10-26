package com.ove.paper.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ove.account.domain.PaperAccount;
import com.ove.account.repository.PaperAccountRepository;
import com.ove.global.exception.BusinessException;
import com.ove.global.exception.ErrorCode;
import com.ove.paper.domain.OrderSide;
import com.ove.paper.domain.PaperHolding;
import com.ove.paper.domain.PaperOrder;
import com.ove.paper.dto.PaperOrderResponse;
import com.ove.paper.dto.PaperPortfolioResponse;
import com.ove.paper.repository.PaperHoldingRepository;
import com.ove.paper.repository.PaperOrderRepository;

/**
 * 모의투자 원장 쓰기만 트랜잭션으로 묶는다.
 * 업비트 시세를 부르는 일은 PaperTradingService가 이 빈을 부르기 전에 끝내서,
 * 외부 시세 응답을 기다리는 동안 DB 커넥션(과 계좌 행 잠금)을 점유하지 않는다.
 * 같은 클래스 안에서 @Transactional 메서드를 부르면 프록시를 거치지 않아 트랜잭션이 안 걸리므로, 일부러 별도 빈으로 나눴다.
 */
@Service
public class PaperTradingWriteService {

    private static final int AMOUNT_SCALE = 8;
    private static final int QUANTITY_SCALE = 12;
    private static final int PRICE_SCALE = 8;

    private final PaperAccountRepository paperAccountRepository;
    private final PaperHoldingRepository paperHoldingRepository;
    private final PaperOrderRepository paperOrderRepository;
    private final BigDecimal initialBalance;
    private final com.ove.signal.repository.SignalRepository signalRepository;

    public PaperTradingWriteService(PaperAccountRepository paperAccountRepository,
                                    PaperHoldingRepository paperHoldingRepository,
                                    PaperOrderRepository paperOrderRepository,
                                    @Value("${paper-account.initial-balance:10000000}") BigDecimal initialBalance,
                                    com.ove.signal.repository.SignalRepository signalRepository) {
        this.paperAccountRepository = paperAccountRepository;
        this.paperHoldingRepository = paperHoldingRepository;
        this.paperOrderRepository = paperOrderRepository;
        this.initialBalance = initialBalance;
        this.signalRepository = signalRepository;
    }

    /**
     * 잔고·보유수량을 검증하고 주문을 즉시 체결한다.
     * price는 호출 전에 서버가 거래소에서 조회한 값이며, 이 메서드 안에서는 외부 호출을 하지 않는다.
     */
    @Transactional
    public PaperOrderResponse placeOrder(UUID userId, String symbol, OrderSide side, BigDecimal quantity,
                                         BigDecimal price) {
        BigDecimal normalizedQuantity = quantity.setScale(QUANTITY_SCALE, RoundingMode.HALF_UP);
        BigDecimal normalizedPrice = price.setScale(PRICE_SCALE, RoundingMode.HALF_UP);
        BigDecimal amount = normalizedPrice.multiply(normalizedQuantity).setScale(AMOUNT_SCALE, RoundingMode.HALF_UP);
        if (amount.signum() == 0) {
            throw new BusinessException(ErrorCode.ORDER_AMOUNT_TOO_SMALL);
        }

        BigDecimal fee = amount.multiply(new BigDecimal("0.0005")).setScale(AMOUNT_SCALE, RoundingMode.HALF_UP);
        PaperAccount account = getAccountWithLock(userId);
        if (side == OrderSide.BUY) {
            buy(account, symbol, normalizedQuantity, normalizedPrice, amount, fee);
        } else {
            sell(account, symbol, normalizedQuantity, amount, fee);
        }

        PaperOrder order = paperOrderRepository.save(PaperOrder.builder()
                .paperAccount(account)
                .symbol(symbol)
                .side(side)
                .quantity(normalizedQuantity)
                .price(normalizedPrice)
                .amount(amount)
                .fee(fee)
                .signalAtOrder(signalRepository.findFirstBySymbolOrderByGeneratedAtDescIdDesc(symbol)
                    .map(com.ove.signal.domain.Signal::getSignal).orElse(null))
                .build());
        return PaperOrderResponse.from(order);
    }

    @Transactional
    public PaperPortfolioResponse reset(UUID userId) {
        PaperAccount account = getAccountWithLock(userId);
        paperOrderRepository.deleteAllByUserId(userId);
        paperHoldingRepository.deleteAllByUserId(userId);
        account.reset(initialBalance);
        return PaperPortfolioResponse.from(account, List.of(), Map.of());
    }

    private void buy(PaperAccount account, String symbol, BigDecimal quantity, BigDecimal price, BigDecimal amount, BigDecimal fee) {
        if (account.getBalance().compareTo(amount.add(fee)) < 0) {
            throw new BusinessException(ErrorCode.INSUFFICIENT_BALANCE);
        }

        PaperHolding holding = paperHoldingRepository.findByPaperAccountIdAndSymbol(account.getId(), symbol)
                .map(existingHolding -> {
                    existingHolding.buy(quantity, price);
                    return existingHolding;
                })
                .orElseGet(() -> PaperHolding.builder()
                        .paperAccount(account)
                        .symbol(symbol)
                        .quantity(quantity)
                        .averagePurchasePrice(price)
                        .build());
        paperHoldingRepository.save(holding);
        account.withdraw(amount.add(fee));
    }

    private void sell(PaperAccount account, String symbol, BigDecimal quantity, BigDecimal amount, BigDecimal fee) {
        PaperHolding holding = paperHoldingRepository.findByPaperAccountIdAndSymbol(account.getId(), symbol)
                .orElseThrow(() -> new BusinessException(ErrorCode.INSUFFICIENT_HOLDING));
        if (holding.getQuantity().compareTo(quantity) < 0) {
            throw new BusinessException(ErrorCode.INSUFFICIENT_HOLDING);
        }

        holding.sell(quantity);
        if (holding.getQuantity().signum() == 0) {
            paperHoldingRepository.delete(holding);
        }
        account.deposit(amount.subtract(fee));
    }

    private PaperAccount getAccountWithLock(UUID userId) {
        return paperAccountRepository.findWithLockByUserId(userId)
                .filter(account -> !account.getUser().isWithdrawn())
                .orElseThrow(() -> new BusinessException(ErrorCode.PAPER_ACCOUNT_NOT_FOUND));
    }
}
