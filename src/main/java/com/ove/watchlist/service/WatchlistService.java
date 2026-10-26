package com.ove.watchlist.service;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ove.global.exception.BusinessException;
import com.ove.global.exception.ErrorCode;
import com.ove.market.client.UpbitMarketResponse;
import com.ove.market.client.UpbitTickerResponse;
import com.ove.market.service.MarketService;
import com.ove.user.repository.UserRepository;
import com.ove.watchlist.domain.Watchlist;
import com.ove.watchlist.dto.WatchlistAddResponse;
import com.ove.watchlist.dto.WatchlistItemResponse;
import com.ove.watchlist.dto.WatchlistListResponse;
import com.ove.watchlist.repository.WatchlistRepository;

import lombok.extern.slf4j.Slf4j;

/**
 * 관심 종목 관리. 종목 존재 여부와 이름·현재가는 MarketService(업비트 캐시)를 그대로 쓴다.
 */
@Slf4j
@Service
public class WatchlistService {

    private final WatchlistRepository watchlistRepository;
    private final UserRepository userRepository;
    private final MarketService marketService;
    private final int maxSize;

    public WatchlistService(WatchlistRepository watchlistRepository, UserRepository userRepository,
                            MarketService marketService, @Value("${watchlist.max-size:20}") int maxSize) {
        this.watchlistRepository = watchlistRepository;
        this.userRepository = userRepository;
        this.marketService = marketService;
        this.maxSize = maxSize;
    }

    // 관심 종목을 추가할 때 사용. 종목 확인(업비트 실패 시 502) → 중복·최대 개수 확인 → 저장 순서다.
    // 같은 회원의 추가 요청이 동시에 와도 중복·최대 개수 확인이 깨지지 않도록 회원 행에 쓰기 잠금을 걸어 한 줄로 세운다.
    // (유일 제약만으로는 같은 종목 중복만 막을 뿐 "최대 20개" 경쟁은 못 막고, 회원별 잠금은 다른 회원끼리 영향이 없다.)
    // 유일 제약은 마지막 안전망이라, 그래도 터지면 WATCHLIST_CONFLICT로 바꿔 준다.
    @Transactional
    public WatchlistAddResponse add(UUID userId, String symbol) {
        String normalized = normalize(symbol);
        marketService.getSupportedMarket(normalized);

        userRepository.findByIdForUpdate(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));

        if (watchlistRepository.existsByUserIdAndSymbol(userId, normalized)
                || watchlistRepository.countByUserId(userId) >= maxSize) {
            throw new BusinessException(ErrorCode.WATCHLIST_CONFLICT);
        }

        try {
            return new WatchlistAddResponse(watchlistRepository.saveAndFlush(
                    Watchlist.builder().userId(userId).symbol(normalized).build()));
        } catch (DataIntegrityViolationException e) {
            throw new BusinessException(ErrorCode.WATCHLIST_CONFLICT);
        }
    }

    // 관심 종목 목록을 등록 순서(오래된 것부터)로 돌려줄 때 사용
    // 업비트가 잠깐 안 되더라도 내 목록 자체는 보여 줘야 해서, 종목명·현재가 조회가 실패하면 그 값만 null로 두고 나머지는 정상 반환한다.
    // signal·confidence는 AI 서버가 생기기 전까지 항상 null이다.
    @Transactional(readOnly = true)
    public WatchlistListResponse getWatchlist(UUID userId) {
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
    @Transactional
    public void remove(UUID userId, String symbol) {
        Watchlist watchlist = watchlistRepository.findByUserIdAndSymbol(userId, normalize(symbol))
                .orElseThrow(() -> new BusinessException(ErrorCode.WATCHLIST_NOT_FOUND));
        watchlistRepository.delete(watchlist);
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
