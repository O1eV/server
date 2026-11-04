package com.ove.model.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * AI 서버 /api/v1/predict 응답. decision은 BUY / SELL / HOLD.
 * model_version은 서버가 로드한 가중치 이름이다. 구버전 서버처럼 필드가 없거나 null이면 null로 둔다.
 * 모르는 필드가 와도 실패하지 않는다.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ModelDecision(String decision, double buy, double sell, double hold, double confidence,
                            @JsonProperty("model_version") String modelVersion) {

    public ModelDecision(String decision, double buy, double sell, double hold, double confidence) {
        this(decision, buy, sell, hold, confidence, null);
    }
}
