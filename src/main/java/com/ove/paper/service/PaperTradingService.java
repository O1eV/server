package com.ove.paper.service;

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

import com.ove.market.service.MarketPriceProvider;
import com.ove.paper.dto.PaperOrderHistoryResponse;
import com.ove.paper.dto.PaperOrderRequest;
import com.ove.paper.dto.PaperOrderResponse;
import com.ove.paper.dto.PaperPortfolioResponse;
import com.ove.paper.repository.PaperOrderRepository;

/**
 * 실제 거래소에는 요청하지 않는 모의투자 원장 서비스.
 * 업비트 시세 조회는 트랜잭션 밖에서 먼저 끝내고, 잠금·체결 같은 DB 작업만 PaperTradingWriteService에 맡긴다.
 */
@Service
public class PaperTradingService {

    private final PaperOrderRepository paperOrderRepository;
    private final MarketPriceProvider marketPriceProvider;
    private final PaperTradingReadService paperTradingReadService;
    private final PaperTradingWriteService paperTradingWriteService;

    public PaperTradingService(PaperOrderRepository paperOrderRepository,
                               MarketPriceProvider marketPriceProvider,
                               PaperTradingReadService paperTradingReadService,
                               PaperTradingWriteService paperTradingWriteService) {
        this.paperOrderRepository = paperOrderRepository;
        this.marketPriceProvider = marketPriceProvider;
        this.paperTradingReadService = paperTradingReadService;
        this.paperTradingWriteService = paperTradingWriteService;
    }

    /**
     * 계좌·보유 종목은 한 읽기 트랜잭션으로 같은 시점에 읽고, 시세는 트랜잭션 밖에서 조회한다.
     * 상장폐지 등으로 일부 종목의 시세를 못 구해도 전체 조회를 실패시키지 않고 평균 매수가로 표시한다.
     */
    public PaperPortfolioResponse getPortfolio(UUID userId) {
        PaperPortfolioSnapshot snapshot = paperTradingReadService.getPortfolioSnapshot(userId);
        List<String> symbols = snapshot.holdings().stream().map(holding -> holding.getSymbol()).toList();
        Map<String, BigDecimal> currentPrices = marketPriceProvider.getPortfolioPrices(symbols);
        return PaperPortfolioResponse.from(snapshot.account(), snapshot.holdings(), currentPrices);
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
     *
     * <p>거래소 종목 목록에 없는 종목(상장폐지·거래 중단)은 보유 중이어도 주문할 수 없다.
     * 매도도 MARKET_NOT_FOUND(404)로 거절되며, 해당 보유 종목은 POST /api/paper/reset으로만 정리할 수 있다.
     * 시세가 없는 종목을 평균 매수가로 청산하는 기능은 MVP 범위에서 제공하지 않는다.
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

    private String normalizeSymbol(String symbol) {
        return symbol.trim().toUpperCase(Locale.ROOT);
    }
}
