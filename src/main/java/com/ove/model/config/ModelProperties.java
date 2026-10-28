package com.ove.model.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * application.yaml의 model.* 설정. AI 모델 서버(/api/v1/predict) 연동은 enabled=true(그리고 collector.enabled=true)일 때만 켜진다.
 * apiKey는 환경변수(MODEL_API_KEY)로만 받고, 로그나 toString에 내보내지 않는다.
 */
@ConfigurationProperties(prefix = "model")
public record ModelProperties(
        @DefaultValue("false") boolean enabled,
        String baseUrl,
        String apiKey,
        // 호출 1회당 연결·응답 제한 시간. 실패하면 한 번만 다시 시도한다.
        @DefaultValue("500ms") Duration timeout,
        // 모델에 보낼 최근 1분봉 개수. 모델이 최소 15개를 요구한다.
        @DefaultValue("30") int candleCount
) {

    public static final int MIN_CANDLES = 15;

    // 켜져 있는데 설정이 잘못되면 기동 때 바로 막는다.
    public void validate() {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalStateException("model.enabled=true이면 model.base-url(MODEL_BASE_URL)이 필요합니다.");
        }
        if (candleCount < MIN_CANDLES) {
            throw new IllegalStateException("model.candle-count는 " + MIN_CANDLES + " 이상이어야 합니다.");
        }
    }

    @Override
    public String toString() {
        return "ModelProperties[enabled=" + enabled + ", baseUrl=" + baseUrl + ", apiKey=" + (hasApiKey() ? "****" : "(none)")
                + ", timeout=" + timeout + ", candleCount=" + candleCount + "]";
    }

    public boolean hasApiKey() {
        return apiKey != null && !apiKey.isBlank();
    }
}
