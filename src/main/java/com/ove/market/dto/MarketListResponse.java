package com.ove.market.dto;

import java.util.List;

import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
public class MarketListResponse {

    private List<MarketResponse> items;
    private int page;
    private int size;
    private long totalElements;

    // 한 페이지 분량의 종목과 페이지 정보를 담을 때 사용
    public MarketListResponse(List<MarketResponse> items, int page, int size, long totalElements) {
        this.items = items;
        this.page = page;
        this.size = size;
        this.totalElements = totalElements;
    }
}
