package com.ove.market.client;

import java.math.BigDecimal;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class UpbitTickerResponse {

    private String market;

    @JsonProperty("trade_price")
    private BigDecimal tradePrice;

    @JsonProperty("signed_change_rate")
    private BigDecimal signedChangeRate;

    // 전일 대비 변동률로 시장 상태(UP / DOWN / SIDEWAYS / VOLATILE)를 정할 때 사용
    // 업비트 응답에 변동률이 없으면 null을 돌려주고, 호출부(MarketService)가 깨진 응답으로 처리한다.
    public String getMarketState() {
        if (signedChangeRate == null) {
            return null;
        }
        double rate = signedChangeRate.doubleValue();
        if (Math.abs(rate) >= 0.05) {
            return "VOLATILE";
        }
        if (rate >= 0.01) {
            return "UP";
        }
        if (rate <= -0.01) {
            return "DOWN";
        }
        return "SIDEWAYS";
    }
}
