package com.ove.market.dto;

import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Schema(description = "캔들 목록(시간 오름차순)")
@Getter
@NoArgsConstructor
public class CandleListResponse {

    @Schema(description = "종목 심볼", example = "KRW-BTC")
    private String symbol;
    @Schema(description = "봉 단위", example = "1m")
    private String interval;
    @Schema(description = "캔들들. 시간 오름차순")
    private List<CandleResponse> candles;

    // 종목·봉 단위와 캔들 목록을 담을 때 사용
    public CandleListResponse(String symbol, String interval, List<CandleResponse> candles) {
        this.symbol = symbol;
        this.interval = interval;
        this.candles = candles;
    }
}
