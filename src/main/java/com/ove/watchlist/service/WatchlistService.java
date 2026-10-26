package com.ove.watchlist.service;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ove.global.exception.BusinessException;
import com.ove.global.exception.ErrorCode;
import com.ove.market.dto.MarketResponse;
import com.ove.market.service.MarketService;
import com.ove.user.domain.User;
import com.ove.user.repository.UserRepository;
import com.ove.watchlist.domain.Watchlist;
import com.ove.watchlist.dto.WatchlistAddResponse;
import com.ove.watchlist.dto.WatchlistListResponse;
import com.ove.watchlist.dto.WatchlistResponse;
import com.ove.watchlist.repository.WatchlistRepository;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class WatchlistService {

    private final WatchlistRepository watchlistRepository;
    private final UserRepository userRepository;
    private final MarketService marketService;

    // 내 관심 종목을 등록한 순서대로 현재가와 함께 조회할 때 사용
    // 업비트를 부르는 동안 DB 커넥션을 잡지 않도록 트랜잭션 없이 조회하고, 업비트가 안 되면 이름·가격만 비워서 돌려준다.
    public WatchlistListResponse getMyWatchlist(UUID userId) {
        List<Watchlist> watchlists = watchlistRepository.findAllByUserIdOrderByIdAsc(userId);

        List<String> symbols = new ArrayList<>();
        for (Watchlist watchlist : watchlists) {
            symbols.add(watchlist.getSymbol());
        }
        List<MarketResponse> markets = getMarketsOrEmpty(symbols);

        List<WatchlistResponse> items = new ArrayList<>();
        for (Watchlist watchlist : watchlists) {
            items.add(new WatchlistResponse(watchlist, findMarket(markets, watchlist.getSymbol())));
        }
        return new WatchlistListResponse(items);
    }

    // 종목 하나를 내 관심 종목에 추가할 때 사용 (업비트를 부르지 않아서 업비트가 안 될 때도 등록된다)
    @Transactional
    public WatchlistAddResponse add(UUID userId, String symbol) {
        String market = symbol.trim().toUpperCase();
        if (!marketService.isSupported(market)) {
            throw new BusinessException(ErrorCode.MARKET_NOT_FOUND);
        }
        if (watchlistRepository.existsByUserIdAndSymbol(userId, market)) {
            throw new BusinessException(ErrorCode.WATCHLIST_ALREADY_EXISTS);
        }
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));

        try {
            Watchlist watchlist = watchlistRepository.saveAndFlush(Watchlist.builder()
                    .user(user)
                    .symbol(market)
                    .build());
            return new WatchlistAddResponse(watchlist);
        } catch (DataIntegrityViolationException e) {
            // 같은 종목 등록 요청이 동시에 와서 위의 중복 확인을 둘 다 통과한 경우
            throw new BusinessException(ErrorCode.WATCHLIST_ALREADY_EXISTS);
        }
    }

    // 내 관심 종목에서 종목 하나를 뺄 때 사용
    @Transactional
    public void remove(UUID userId, String symbol) {
        int deleted = watchlistRepository.deleteByUserIdAndSymbol(userId, symbol.trim().toUpperCase());
        if (deleted == 0) {
            throw new BusinessException(ErrorCode.WATCHLIST_NOT_FOUND);
        }
    }

    // 업비트에서 종목 이름·현재가를 가져오고, 실패하면 빈 목록으로 대신할 때 사용
    private List<MarketResponse> getMarketsOrEmpty(List<String> symbols) {
        try {
            return marketService.getMarketsBySymbols(symbols);
        } catch (BusinessException e) {
            return List.of();
        }
    }

    // 종목 정보 목록에서 심볼이 같은 종목을 찾을 때 사용 (없으면 null)
    private MarketResponse findMarket(List<MarketResponse> markets, String symbol) {
        for (MarketResponse market : markets) {
            if (market.getSymbol().equals(symbol)) {
                return market;
            }
        }
        return null;
    }
}
