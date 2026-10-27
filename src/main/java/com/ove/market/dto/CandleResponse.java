package com.ove.market.dto;

import java.math.BigDecimal;
import java.time.Instant;

import com.ove.market.client.UpbitCandleResponse;
import com.ove.market.collector.MinuteCandle;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Schema(description = "1개의 캔들(OHLCV)")
@Getter
@NoArgsConstructor
public class CandleResponse {

    @Schema(description = "캔들이 시작하는 시각(UTC, ISO-8601)", example = "2026-10-24T03:00:00Z")
    private Instant time;
    @Schema(description = "시가", example = "111276000")
    private BigDecimal open;
    @Schema(description = "고가", example = "111277000")
    private BigDecimal high;
    @Schema(description = "저가", example = "111275000")
    private BigDecimal low;
    @Schema(description = "종가", example = "111275000")
    private BigDecimal close;
    @Schema(description = "거래량", example = "0.07983422")
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

    // 저장된 1분봉(키움 수집분)으로 캔들 응답을 만들 때 사용
    public CandleResponse(MinuteCandle candle) {
        this.time = candle.time();
        this.open = candle.open();
        this.high = candle.high();
        this.low = candle.low();
        this.close = candle.close();
        this.volume = candle.volume();
    }
}
