package com.ove.watchlist.service;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import com.ove.market.service.DemoStockService;

import com.ove.global.exception.BusinessException;
import com.ove.global.exception.ErrorCode;
import com.ove.user.repository.UserRepository;
import com.ove.market.client.UpbitMarketResponse;
import com.ove.market.client.UpbitTickerResponse;
import com.ove.market.service.MarketService;
import com.ove.watchlist.domain.Watchlist;
import com.ove.watchlist.dto.WatchlistAddResponse;
import com.ove.watchlist.dto.WatchlistItemResponse;
import com.ove.watchlist.dto.WatchlistListResponse;
import com.ove.watchlist.repository.WatchlistRepository;

import lombok.extern.slf4j.Slf4j;

/**
 * 관심 종목 관리. 종목 존재 여부와 이름·현재가는 MarketService(업비트 캐시)를 그대로 쓰고, 쓰기는 WatchlistWriteService에 맡긴다.
 */
@Slf4j
@Service
public class WatchlistService {

    private final WatchlistRepository watchlistRepository;
    private final WatchlistWriteService writeService;
    private final MarketService marketService;
    private final UserRepository userRepository;
    private final ObjectProvider<DemoStockService> demoStocks;

    public WatchlistService(WatchlistRepository watchlistRepository, WatchlistWriteService writeService,
                            MarketService marketService, UserRepository userRepository) {
        this(watchlistRepository, writeService, marketService, userRepository, null);
    }

    @Autowired
    public WatchlistService(WatchlistRepository watchlistRepository, WatchlistWriteService writeService,
                            MarketService marketService, UserRepository userRepository,
                            ObjectProvider<DemoStockService> demoStocks) {
        this.demoStocks = demoStocks;
        this.watchlistRepository = watchlistRepository;
        this.writeService = writeService;
        this.marketService = marketService;
        this.userRepository = userRepository;
    }

    // 관심 종목을 추가할 때 사용. 종목 확인(업비트 실패 시 502)을 트랜잭션 밖에서 먼저 끝낸 뒤,
    // 중복·최대 개수 확인과 저장만 WatchlistWriteService의 트랜잭션에 맡긴다.
    public WatchlistAddResponse add(UUID userId, String symbol) {
        String normalized = normalize(symbol);
        if (normalized.matches("[0-9]{6}")) {
            // prod 또는 수집 비활성 환경에서는 빈 자체가 없어 주식 신규 등록을 차단한다.
            DemoStockService service = demoStocks == null ? null : demoStocks.getIfAvailable();
            if (service == null || !service.supportsSymbol(normalized)) {
                throw new BusinessException(ErrorCode.MARKET_NOT_FOUND);
            }
        } else {
            marketService.getSupportedMarket(normalized);
        }
        return writeService.add(userId, normalized);
    }

    // 관심 종목 목록을 등록 순서(오래된 것부터)로 돌려줄 때 사용
    // 업비트가 잠깐 안 되더라도 내 목록 자체는 보여 줘야 해서, 종목명·현재가 조회가 실패하면 그 값만 null로 두고 나머지는 정상 반환한다.
    // signal·confidence는 AI 서버가 생기기 전까지 항상 null이다.
    // 조회에는 트랜잭션을 걸지 않는다. 업비트를 부르는 동안 DB 커넥션을 잡고 있지 않게 한다.
    public WatchlistListResponse getWatchlist(UUID userId) {
        if (!userRepository.existsById(userId)) {
            throw new BusinessException(ErrorCode.USER_NOT_FOUND);
        }
        List<Watchlist> watchlists = watchlistRepository.findByUserIdOrderByAddedAtAscIdAsc(userId);
        if (watchlists.isEmpty()) {
            return new WatchlistListResponse(List.of());
        }

        Map<String, String> koreanNames = fetchKoreanNames();
        Map<String, BigDecimal> prices = fetchPrices();

        List<WatchlistItemResponse> items = watchlists.stream()
                .map(w -> WatchlistItemResponse.builder()
                        .symbol(w.getSymbol())
                        .koreanName(koreanNames.get(w.getSymbol()))
                        .price(prices.get(w.getSymbol()))
                        .addedAt(w.getAddedAt())
                        .build())
                .toList();
        return new WatchlistListResponse(items);
    }

    // 관심 종목에서 뺄 때 사용. 종목 코드가 지금 지원 목록에 없어도 이미 등록된 건 지울 수 있어야 해서 업비트는 부르지 않는다.
    public void remove(UUID userId, String symbol) {
        writeService.remove(userId, normalize(symbol));
    }

    private Map<String, String> fetchKoreanNames() {
        Map<String, String> names = new HashMap<>();
        try {
            for (UpbitMarketResponse market : marketService.getSupportedMarkets()) {
                names.put(market.getMarket(), market.getKoreanName());
            }
        } catch (BusinessException e) {
            log.warn("관심 종목 목록: 종목명 조회 실패, null로 대체: {}", e.getErrorCode());
        }
        return names;
    }

    private Map<String, BigDecimal> fetchPrices() {
        Map<String, BigDecimal> prices = new HashMap<>();
        try {
            for (UpbitTickerResponse ticker : marketService.getSupportedTickers()) {
                prices.put(ticker.getMarket(), ticker.getTradePrice());
            }
        } catch (BusinessException e) {
            log.warn("관심 종목 목록: 현재가 조회 실패, null로 대체: {}", e.getErrorCode());
        }
        return prices;
    }

    // KRW-btc, " krw-btc " 같은 입력을 KRW-BTC로 맞출 때 사용
    private String normalize(String symbol) {
        return symbol.trim().toUpperCase(Locale.ROOT);
    }
}
