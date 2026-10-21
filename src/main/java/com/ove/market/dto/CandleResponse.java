package com.ove.market.dto;

import java.math.BigDecimal;
import java.time.Instant;

import com.ove.market.client.UpbitCandleResponse;

import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
public class CandleResponse {

    private Instant time;
    private BigDecimal open;
    private BigDecimal high;
    private BigDecimal low;
    private BigDecimal close;
    private BigDecimal volume;

    // 업비트 캔들로 차트용 캔들 응답을 만들 때 사용
    public CandleResponse(UpbitCandleResponse candle) {
        this.time = candle.toInstant();
        this.open = candle.getOpeningPrice();
        this.high = candle.getHighPrice();
        this.low = candle.getLowPrice();
        this.close = candle.getTradePrice();
        this.volume = candle.getCandleAccTradeVolume();
    }
}
