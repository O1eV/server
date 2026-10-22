package com.ove.market.dto;

import java.util.List;

import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
public class MarketSearchListResponse {

    private List<MarketSearchResponse> items;

    // 검색 결과 목록을 담을 때 사용
    public MarketSearchListResponse(List<MarketSearchResponse> items) {
        this.items = items;
    }
}
