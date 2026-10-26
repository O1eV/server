package com.ove.watchlist.dto;

import java.time.Instant;
import java.math.BigDecimal;
import java.util.List;
import com.ove.watchlist.domain.Watchlist;

public record WatchlistResponse(List<Item> items) {
    public record Item(String symbol, String koreanName, BigDecimal price, String signal,
                       BigDecimal confidence, Instant addedAt) {}
    public record Added(String symbol, Instant addedAt) {
        public static Added from(Watchlist entry) {
            return new Added(entry.getSymbol(), entry.getAddedAt());
        }
    }
}
