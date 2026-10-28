package com.ove.model.service;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.Executor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Redis candles.closed:{symbol} 채널의 마감봉 알림을 받아 신호 생성을 별도 스레드에 넘긴다.
 * Redis 수신 스레드와 수집기를 모델 호출(최대 약 1초)로 막지 않고, 어떤 예외도 밖으로 내보내지 않는다.
 */
public class CandleClosedListener implements MessageListener {

    private static final Logger log = LoggerFactory.getLogger(CandleClosedListener.class);

    private final SignalGenerationService service;
    private final ObjectMapper objectMapper;
    private final Executor executor;
    private final List<String> symbols;

    public CandleClosedListener(SignalGenerationService service, ObjectMapper objectMapper, Executor executor,
                                List<String> symbols) {
        this.service = service;
        this.objectMapper = objectMapper;
        this.executor = executor;
        this.symbols = symbols;
    }

    @Override
    public void onMessage(Message message, byte[] pattern) {
        try {
            JsonNode candle = objectMapper.readTree(message.getBody());
            String symbol = candle.path("symbol").asString();
            Instant time = Instant.parse(candle.path("time").asString());
            if (!symbols.contains(symbol)) {
                return;
            }
            executor.execute(() -> run(symbol, time));
        } catch (RuntimeException e) {
            // 대기열이 가득 찬 경우 포함. 신호를 한 번 건너뛸 뿐 수집에는 영향이 없다.
            log.warn("마감봉 알림 처리 실패: {}", e.getClass().getSimpleName());
        }
    }

    private void run(String symbol, Instant time) {
        try {
            service.generate(symbol, time);
        } catch (RuntimeException e) {
            log.warn("신호 생성 실패: symbol={}, cause={}", symbol, e.getClass().getSimpleName());
        }
    }
}
