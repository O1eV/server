package com.ove.watchlist.dto;

import java.util.List;

import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
public class WatchlistListResponse {

    private List<WatchlistResponse> items;
    private long totalElements;

    // 관심 종목 목록과 전체 개수를 담을 때 사용
    public WatchlistListResponse(List<WatchlistResponse> items) {
        this.items = items;
        this.totalElements = items.size();
    }
}
