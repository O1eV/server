package com.ove.paper.service;

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

import com.ove.account.domain.PaperAccount;
import com.ove.account.repository.PaperAccountRepository;
import com.ove.global.exception.BusinessException;
import com.ove.global.exception.ErrorCode;
import com.ove.market.service.MarketPriceProvider;
import com.ove.paper.domain.PaperHolding;
import com.ove.paper.dto.PaperOrderHistoryResponse;
import com.ove.paper.dto.PaperOrderRequest;
import com.ove.paper.dto.PaperOrderResponse;
import com.ove.paper.dto.PaperPortfolioResponse;
import com.ove.paper.repository.PaperHoldingRepository;
import com.ove.paper.repository.PaperOrderRepository;

/**
 * 실제 거래소에는 요청하지 않는 모의투자 원장 서비스.
 * 업비트 시세 조회는 트랜잭션 밖에서 먼저 끝내고, 잠금·체결 같은 DB 작업만 PaperTradingWriteService에 맡긴다.
 */
@Service
public class PaperTradingService {

    private final PaperAccountRepository paperAccountRepository;
    private final PaperHoldingRepository paperHoldingRepository;
    private final PaperOrderRepository paperOrderRepository;
    private final MarketPriceProvider marketPriceProvider;
    private final PaperTradingWriteService paperTradingWriteService;

    public PaperTradingService(PaperAccountRepository paperAccountRepository,
                               PaperHoldingRepository paperHoldingRepository,
                               PaperOrderRepository paperOrderRepository,
                               MarketPriceProvider marketPriceProvider,
                               PaperTradingWriteService paperTradingWriteService) {
        this.paperAccountRepository = paperAccountRepository;
        this.paperHoldingRepository = paperHoldingRepository;
        this.paperOrderRepository = paperOrderRepository;
        this.marketPriceProvider = marketPriceProvider;
        this.paperTradingWriteService = paperTradingWriteService;
    }

    /**
     * 보유 종목과 계좌는 짧은 읽기 트랜잭션으로 먼저 읽고, 시세는 트랜잭션 밖에서 조회한다.
     */
    public PaperPortfolioResponse getPortfolio(UUID userId) {
        PaperAccount account = getAccount(userId);
        List<PaperHolding> holdings = paperHoldingRepository.findAllByPaperAccountUserIdOrderBySymbolAsc(userId);
        List<String> symbols = holdings.stream().map(PaperHolding::getSymbol).toList();
        Map<String, BigDecimal> currentPrices = marketPriceProvider.getLatestPrices(symbols);
        return PaperPortfolioResponse.from(account, holdings, currentPrices);
    }

    public PaperOrderHistoryResponse getOrders(UUID userId, String symbol, int page, int size) {
        // 같은 시각에 체결된 주문이 페이지 경계에서 중복·누락되지 않도록 id를 보조 정렬로 둔다.
        PageRequest pageable = PageRequest.of(page, size,
                Sort.by(Sort.Order.desc("orderedAt"), Sort.Order.desc("id")));
        if (symbol == null || symbol.isBlank()) {
            return PaperOrderHistoryResponse.from(paperOrderRepository.findAllByPaperAccountUserId(userId, pageable));
        }
        return PaperOrderHistoryResponse.from(paperOrderRepository.findAllByPaperAccountUserIdAndSymbol(userId,
                normalizeSymbol(symbol), pageable));
    }

    /**
     * 종목 존재 확인과 시세 조회를 트랜잭션 밖에서 끝낸 뒤, 체결만 짧은 트랜잭션으로 처리한다.
     * 체결가는 클라이언트 입력이 아니라 서버가 조회한 최신가만 사용한다.
     */
    public PaperOrderResponse placeOrder(UUID userId, PaperOrderRequest request) {
        String symbol = normalizeSymbol(request.getSymbol());
        marketPriceProvider.validateMarket(symbol);
        BigDecimal price = marketPriceProvider.getLatestPrice(symbol);
        return paperTradingWriteService.placeOrder(userId, symbol, request.getSide(), request.getQuantity(), price);
    }

    public PaperPortfolioResponse reset(UUID userId) {
        return paperTradingWriteService.reset(userId);
    }

    private PaperAccount getAccount(UUID userId) {
        return paperAccountRepository.findByUserId(userId)
                .filter(account -> !account.getUser().isWithdrawn())
                .orElseThrow(() -> new BusinessException(ErrorCode.PAPER_ACCOUNT_NOT_FOUND));
    }

    private String normalizeSymbol(String symbol) {
        return symbol.trim().toUpperCase(Locale.ROOT);
    }
}
