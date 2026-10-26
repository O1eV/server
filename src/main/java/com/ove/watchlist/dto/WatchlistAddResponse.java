package com.ove.watchlist.dto;

import java.time.Instant;

import com.ove.watchlist.domain.Watchlist;

import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
public class WatchlistAddResponse {

    private String symbol;
    private Instant addedAt;

    // 방금 등록한 관심 종목을 응답으로 돌려줄 때 사용
    public WatchlistAddResponse(Watchlist watchlist) {
        this.symbol = watchlist.getSymbol();
        this.addedAt = watchlist.getCreatedAt();
    }
}
