package com.ove.watchlist.dto;

import java.time.Instant;

import com.ove.watchlist.domain.Watchlist;

import lombok.Getter;

@Getter
public class WatchlistAddResponse {

    private final String symbol;
    private final Instant addedAt;

    public WatchlistAddResponse(Watchlist watchlist) {
        this.symbol = watchlist.getSymbol();
        this.addedAt = watchlist.getAddedAt();
    }
}
