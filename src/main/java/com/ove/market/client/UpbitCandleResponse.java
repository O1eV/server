package com.ove.market.client;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.ove.global.exception.BusinessException;
import com.ove.global.exception.ErrorCode;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class UpbitCandleResponse {

    @JsonProperty("candle_date_time_utc")
    private String candleDateTimeUtc;

    @JsonProperty("opening_price")
    private BigDecimal openingPrice;

    @JsonProperty("high_price")
    private BigDecimal highPrice;

    @JsonProperty("low_price")
    private BigDecimal lowPrice;

    @JsonProperty("trade_price")
    private BigDecimal tradePrice;

    @JsonProperty("candle_acc_trade_volume")
    private BigDecimal candleAccTradeVolume;

    // 업비트가 UTC 기준으로 주는 시각(예: 2026-10-19T12:00:00)을 Instant로 바꿀 때 사용
    public Instant toInstant() {
        try {
            return LocalDateTime.parse(candleDateTimeUtc).toInstant(ZoneOffset.UTC);
        } catch (DateTimeParseException | NullPointerException e) {
            // 업비트 응답 형식이 바뀐 경우이므로 서버 오류가 아니라 외부 서버 문제로 응답한다.
            throw new BusinessException(ErrorCode.UPSTREAM_UNAVAILABLE, "시세 서버의 응답 형식이 올바르지 않습니다.");
        }
    }
}
