package com.ove.watchlist.service;

import java.util.Locale;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.dao.DataIntegrityViolationException;
import com.ove.global.exception.BusinessException;
import com.ove.global.exception.ErrorCode;
import com.ove.market.service.MarketService;
import com.ove.user.repository.UserRepository;
import com.ove.watchlist.domain.Watchlist;
import com.ove.watchlist.dto.WatchlistResponse;
import com.ove.watchlist.repository.WatchlistRepository;
import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class WatchlistService {
    private final MarketService marketService;
    private final WatchlistRepository watchlistRepository;
    private final UserRepository userRepository;

    public WatchlistResponse getWatchlist(UUID userId) {
        checkUser(userId);
        var entries = watchlistRepository.findByUserIdOrderByAddedAtAscSymbolAsc(userId);
        if (entries.isEmpty()) return new WatchlistResponse(java.util.List.of());
        var markets = marketService.getMarkets(null, null, null, 0, 100).getItems();
        return new WatchlistResponse(entries.stream().map(entry -> {
            var market = markets.stream().filter(item -> item.getSymbol().equals(entry.getSymbol())).findFirst();
            return new WatchlistResponse.Item(entry.getSymbol(),
                    market.map(item -> item.getKoreanName()).orElse(entry.getSymbol()),
                    market.map(item -> item.getPrice()).orElse(null),
                    market.map(item -> item.getSignal()).orElse(null),
                    market.map(item -> item.getConfidence()).orElse(null), entry.getAddedAt());
        }).toList());
    }

    @Transactional
    public WatchlistResponse.Added add(UUID userId, String symbol) {
        // 사용자 행 잠금으로 동시 등록도 최대 개수를 넘지 않게 직렬화한다.
        userRepository.findForWatchlistUpdate(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));
        String normalized = normalize(symbol);
        MarketService.requireSupportedSymbol(normalized);
        if (watchlistRepository.existsByUserIdAndSymbol(userId, normalized)) {
            throw new BusinessException(ErrorCode.WATCHLIST_ALREADY_EXISTS);
        }
        if (watchlistRepository.countByUserId(userId) >= 20) {
            throw new BusinessException(ErrorCode.WATCHLIST_LIMIT_EXCEEDED);
        }
        try {
            return WatchlistResponse.Added.from(watchlistRepository.saveAndFlush(new Watchlist(userId, normalized)));
        } catch (DataIntegrityViolationException exception) {
            // 동시 등록도 DB의 복합 unique 제약으로 중복을 방지한다.
            throw new BusinessException(ErrorCode.WATCHLIST_ALREADY_EXISTS);
        }
    }

    @Transactional
    public void remove(UUID userId, String symbol) {
        checkUser(userId);
        if (watchlistRepository.deleteByUserIdAndSymbol(userId, normalize(symbol)) == 0) {
            throw new BusinessException(ErrorCode.WATCHLIST_NOT_FOUND);
        }
    }

    private String normalize(String symbol) {
        if (symbol == null || symbol.isBlank()) throw new BusinessException(ErrorCode.INVALID_REQUEST);
        return symbol.trim().toUpperCase(Locale.ROOT);
    }

    private void checkUser(UUID userId) {
        if (!userRepository.existsById(userId)) throw new BusinessException(ErrorCode.USER_NOT_FOUND);
    }
}
