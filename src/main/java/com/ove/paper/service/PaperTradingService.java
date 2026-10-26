package com.ove.paper.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ove.account.domain.PaperAccount;
import com.ove.account.repository.PaperAccountRepository;
import com.ove.global.exception.BusinessException;
import com.ove.global.exception.ErrorCode;
import com.ove.market.service.MarketPriceProvider;
import com.ove.paper.domain.OrderSide;
import com.ove.paper.domain.PaperHolding;
import com.ove.paper.domain.PaperOrder;
import com.ove.paper.dto.PaperOrderHistoryResponse;
import com.ove.paper.dto.PaperOrderRequest;
import com.ove.paper.dto.PaperOrderResponse;
import com.ove.paper.dto.PaperPortfolioResponse;
import com.ove.paper.repository.PaperHoldingRepository;
import com.ove.paper.repository.PaperOrderRepository;

/**
 * 실제 거래소에는 요청하지 않는 모의투자 원장 서비스.
 */
@Service
@Transactional(readOnly = true)
public class PaperTradingService {

    private static final int AMOUNT_SCALE = 8;

    private final PaperAccountRepository paperAccountRepository;
    private final PaperHoldingRepository paperHoldingRepository;
    private final PaperOrderRepository paperOrderRepository;
    private final MarketPriceProvider marketPriceProvider;
    private final BigDecimal initialBalance;

    public PaperTradingService(PaperAccountRepository paperAccountRepository,
                               PaperHoldingRepository paperHoldingRepository,
                               PaperOrderRepository paperOrderRepository,
                               MarketPriceProvider marketPriceProvider,
                               @Value("${paper-account.initial-balance:10000000}") BigDecimal initialBalance) {
        this.paperAccountRepository = paperAccountRepository;
        this.paperHoldingRepository = paperHoldingRepository;
        this.paperOrderRepository = paperOrderRepository;
        this.marketPriceProvider = marketPriceProvider;
        this.initialBalance = initialBalance;
    }

    public PaperPortfolioResponse getPortfolio(UUID userId) {
        PaperAccount account = getAccount(userId);
        List<PaperHolding> holdings = paperHoldingRepository.findAllByPaperAccountUserIdOrderBySymbolAsc(userId);
        List<String> symbols = holdings.stream().map(PaperHolding::getSymbol).toList();
        Map<String, BigDecimal> currentPrices = marketPriceProvider.getLatestPrices(symbols);
        return PaperPortfolioResponse.from(account, holdings, currentPrices);
    }

    public PaperOrderHistoryResponse getOrders(UUID userId, String symbol, int page, int size) {
        PageRequest pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "orderedAt"));
        if (symbol == null || symbol.isBlank()) {
            return PaperOrderHistoryResponse.from(paperOrderRepository.findAllByPaperAccountUserId(userId, pageable));
        }
        return PaperOrderHistoryResponse.from(paperOrderRepository.findAllByPaperAccountUserIdAndSymbol(userId,
                normalizeSymbol(symbol), pageable));
    }

    @Transactional
    public PaperOrderResponse placeOrder(UUID userId, PaperOrderRequest request) {
        String symbol = normalizeSymbol(request.getSymbol());
        BigDecimal quantity = request.getQuantity().setScale(12, RoundingMode.HALF_UP);
        // 체결가는 클라이언트 입력이 아니라 서버가 조회한 최신가만 사용한다.
        BigDecimal price = marketPriceProvider.getLatestPrice(symbol).setScale(8, RoundingMode.HALF_UP);
        BigDecimal amount = price.multiply(quantity).setScale(AMOUNT_SCALE, RoundingMode.HALF_UP);
        if (amount.signum() == 0) {
            throw new BusinessException(ErrorCode.ORDER_AMOUNT_TOO_SMALL);
        }

        // 외부 시세 응답을 기다리는 동안 계좌 행 잠금을 점유하지 않도록 가격 검증 후 잠근다.
        PaperAccount account = getAccountWithLock(userId);
        if (request.getSide() == OrderSide.BUY) {
            buy(account, symbol, quantity, price, amount);
        } else {
            sell(account, symbol, quantity, amount);
        }

        PaperOrder order = paperOrderRepository.save(PaperOrder.builder()
                .paperAccount(account)
                .symbol(symbol)
                .side(request.getSide())
                .quantity(quantity)
                .price(price)
                .amount(amount)
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

    private void buy(PaperAccount account, String symbol, BigDecimal quantity, BigDecimal price, BigDecimal amount) {
        if (account.getBalance().compareTo(amount) < 0) {
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
        account.withdraw(amount);
    }

    private void sell(PaperAccount account, String symbol, BigDecimal quantity, BigDecimal amount) {
        PaperHolding holding = paperHoldingRepository.findByPaperAccountIdAndSymbol(account.getId(), symbol)
                .orElseThrow(() -> new BusinessException(ErrorCode.INSUFFICIENT_HOLDING));
        if (holding.getQuantity().compareTo(quantity) < 0) {
            throw new BusinessException(ErrorCode.INSUFFICIENT_HOLDING);
        }

        holding.sell(quantity);
        if (holding.getQuantity().signum() == 0) {
            paperHoldingRepository.delete(holding);
        }
        account.deposit(amount);
    }

    private PaperAccount getAccount(UUID userId) {
        return paperAccountRepository.findByUserId(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.PAPER_ACCOUNT_NOT_FOUND));
    }

    private PaperAccount getAccountWithLock(UUID userId) {
        return paperAccountRepository.findWithLockByUserId(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.PAPER_ACCOUNT_NOT_FOUND));
    }

    private String normalizeSymbol(String symbol) {
        return symbol.trim().toUpperCase(Locale.ROOT);
    }
}
