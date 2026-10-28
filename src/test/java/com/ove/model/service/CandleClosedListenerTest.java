package com.ove.model.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.Executor;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.DefaultMessage;

import tools.jackson.databind.json.JsonMapper;

class CandleClosedListenerTest {

    SignalGenerationService service = mock(SignalGenerationService.class);
    Executor direct = Runnable::run;
    CandleClosedListener listener = new CandleClosedListener(service, JsonMapper.builder().build(), direct,
            List.of("KRW-BTC"));

    private DefaultMessage message(String json) {
        return new DefaultMessage("candles.closed:KRW-BTC".getBytes(StandardCharsets.UTF_8),
                json.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("마감봉 알림을 받으면 해당 종목·시각으로 신호 생성을 넘긴다")
    void dispatches() {
        listener.onMessage(message("{\"symbol\":\"KRW-BTC\",\"time\":\"2026-10-26T00:19:00Z\"}"), null);

        verify(service).generate("KRW-BTC", Instant.parse("2026-10-26T00:19:00Z"));
    }

    @Test
    @DisplayName("수집 대상이 아닌 종목(주식 코드 등)과 깨진 메시지는 무시하고 예외를 내지 않는다")
    void ignoresUnknownAndMalformed() {
        listener.onMessage(message("{\"symbol\":\"005930\",\"time\":\"2026-10-26T00:19:00Z\"}"), null);
        listener.onMessage(message("not json"), null);
        listener.onMessage(message("{\"symbol\":\"KRW-BTC\"}"), null);

        verify(service, never()).generate(any(), any());
    }

    @Test
    @DisplayName("신호 생성이 예외를 던져도 밖으로 퍼지지 않는다")
    void isolatesFailures() {
        doThrow(new IllegalStateException("boom")).when(service).generate(any(), any());

        listener.onMessage(message("{\"symbol\":\"KRW-BTC\",\"time\":\"2026-10-26T00:19:00Z\"}"), null);

        verify(service).generate(any(), any());
    }
}
