package com.ove.market.dto;

import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Schema(description = "종목 목록 페이지")
@Getter
@NoArgsConstructor
public class MarketListResponse {

    @Schema(description = "이 페이지의 종목들")
    private List<MarketResponse> items;
    @Schema(description = "현재 페이지(0부터)", example = "0")
    private int page;
    @Schema(description = "페이지 크기", example = "20")
    private int size;
    @Schema(description = "필터에 맞는 전체 종목 수", example = "3")
    private long totalElements;

    // 한 페이지 분량의 종목과 페이지 정보를 담을 때 사용
    public MarketListResponse(List<MarketResponse> items, int page, int size, long totalElements) {
        this.items = items;
        this.page = page;
        this.size = size;
        this.totalElements = totalElements;
    }
}
