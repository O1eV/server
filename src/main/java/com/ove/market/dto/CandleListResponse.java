package com.ove.market.dto;

import java.util.List;

import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
public class CandleListResponse {

    private String symbol;
    private String interval;
    private List<CandleResponse> candles;

    // 종목·봉 단위와 캔들 목록을 담을 때 사용
    public CandleListResponse(String symbol, String interval, List<CandleResponse> candles) {
        this.symbol = symbol;
        this.interval = interval;
        this.candles = candles;
    }
}
