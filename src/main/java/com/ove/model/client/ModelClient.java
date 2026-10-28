package com.ove.model.client;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import com.ove.global.exception.BusinessException;
import com.ove.global.exception.ErrorCode;
import com.ove.market.collector.MinuteCandle;
import com.ove.model.config.ModelProperties;

/**
 * AI 모델 서버 호출. 캔들을 오래된 것부터 보내 BUY/SELL/HOLD 확률을 받는다.
 * 제한 시간(기본 500ms) 안에 못 받으면 한 번만 다시 시도하고, 그래도 실패하면 MODEL_UNAVAILABLE을 던진다.
 * 로그에는 키와 응답 본문을 남기지 않는다.
 */
public class ModelClient {

    private static final Logger log = LoggerFactory.getLogger(ModelClient.class);
    private static final String PREDICT_PATH = "/api/v1/predict";
    private static final int MAX_ATTEMPTS = 2;

    private final RestClient restClient;

    public ModelClient(ModelProperties properties) {
        HttpClient httpClient = HttpClient.newBuilder().connectTimeout(properties.timeout()).build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(properties.timeout());
        RestClient.Builder builder = RestClient.builder().baseUrl(properties.baseUrl()).requestFactory(requestFactory);
        if (properties.hasApiKey()) {
            builder.defaultHeader("X-API-KEY", properties.apiKey());
        }
        this.restClient = builder.build();
    }

    public ModelDecision predict(List<MinuteCandle> candles) {
        List<Map<String, Object>> body = new ArrayList<>();
        for (MinuteCandle candle : candles) {
            body.add(Map.of("open", candle.open(), "high", candle.high(), "low", candle.low(), "close", candle.close(),
                    "volume", candle.volume(), "bar_time", candle.time().toString()));
        }
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                ModelDecision decision = restClient.post().uri(PREDICT_PATH).contentType(MediaType.APPLICATION_JSON)
                        .body(body).retrieve().body(ModelDecision.class);
                if (decision == null || decision.decision() == null) {
                    throw new IllegalStateException("빈 응답");
                }
                return decision;
            } catch (HttpClientErrorException e) {
                // 4xx(키 오류·잘못된 요청)는 다시 보내도 같아서 재시도하지 않는다.
                log.warn("모델 서버가 요청을 거절했습니다: status={}", e.getStatusCode().value());
                throw new BusinessException(ErrorCode.MODEL_UNAVAILABLE);
            } catch (RuntimeException e) {
                log.warn("모델 호출 실패({}/{}): {}", attempt, MAX_ATTEMPTS, e.getClass().getSimpleName());
            }
        }
        throw new BusinessException(ErrorCode.MODEL_UNAVAILABLE);
    }
}
