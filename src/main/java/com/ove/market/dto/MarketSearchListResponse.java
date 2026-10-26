package com.ove.market.dto;

import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Schema(description = "종목 검색 결과")
@Getter
@NoArgsConstructor
public class MarketSearchListResponse {

    @Schema(description = "검색에 걸린 종목들")
    private List<MarketSearchResponse> items;

    // 검색 결과 목록을 담을 때 사용
    public MarketSearchListResponse(List<MarketSearchResponse> items) {
        this.items = items;
    }
}
