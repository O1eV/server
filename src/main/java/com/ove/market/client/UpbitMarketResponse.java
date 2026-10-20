package com.ove.market.client;

import java.util.Map;

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
public class UpbitMarketResponse {

    private String market;

    @JsonProperty("korean_name")
    private String koreanName;

    @JsonProperty("english_name")
    private String englishName;

    @JsonProperty("market_event")
    private MarketEvent marketEvent;

    // 업비트 경고 정보를 NORMAL / CAUTION / WARNING으로 바꿀 때 사용
    public String getMarketState() {
        if (marketEvent == null) {
            return "NORMAL";
        }
        if (marketEvent.isWarning()) {
            return "WARNING";
        }
        if (marketEvent.getCaution() != null && marketEvent.getCaution().containsValue(true)) {
            return "CAUTION";
        }
        return "NORMAL";
    }

    @Getter
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class MarketEvent {

        private boolean warning;
        private Map<String, Boolean> caution;
    }
}
