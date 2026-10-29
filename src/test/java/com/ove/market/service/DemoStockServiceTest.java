package com.ove.market.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import com.ove.global.exception.BusinessException;
import com.ove.global.exception.ErrorCode;
import com.ove.market.collector.CandleRepository;
import com.ove.market.collector.MinuteCandle;

class DemoStockServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-26T00:00:10Z");

    StringRedisTemplate redis;
    HashOperations<String, Object, Object> hash;
    CandleRepository candles;
    DemoStockService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        hash = mock(HashOperations.class);
        candles = mock(CandleRepository.class);
        given(redis.opsForHash()).willReturn(hash);
        service = new DemoStockService(redis, List.of("005930"), candles, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private Map<Object, Object> price(String updatedAt) {
        return Map.of("price", "71200", "tradeTimestamp", updatedAt, "updatedAt", updatedAt);
    }

    private void assertError(Runnable call, ErrorCode code) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(code));
    }

    @Test
    void readsSignedChangeRateAndKeepsMissingAsNull() {
        Map<Object, Object> values = new java.util.HashMap<>(price("2026-10-26T00:00:09Z"));
        values.put("changeRate", "-0.0125");
        given(hash.entries("demo:price:005930")).willReturn(values);
        assertThat(service.getPrice("005930").changeRate()).isEqualByComparingTo("-0.0125");
        values.remove("changeRate");
        assertThat(service.getPrice("005930").changeRate()).isNull();
        values.put("changeRate", "invalid");
        assertThat(service.getPrice("005930").price()).isEqualByComparingTo("71200");
        assertThat(service.getPrice("005930").changeRate()).isNull();
    }

    @Test
    @DisplayName("demo:price:{code} 해시를 읽어 현재가를 돌려준다")
    void readsDemoPrice() {
        given(hash.entries("demo:price:005930")).willReturn(price("2026-10-26T00:00:09Z"));

        var result = service.getPrice("005930");

        assertThat(result.symbol()).isEqualTo("005930");
        assertThat(result.price()).isEqualByComparingTo("71200");
        assertThat(result.stale()).isFalse();
    }

    @Test
    @DisplayName("10초 넘게 갱신되지 않으면 stale=true")
    void staleWhenOld() {
        given(hash.entries("demo:price:005930")).willReturn(price("2026-10-25T23:59:59Z"));

        assertThat(service.getPrice("005930").stale()).isTrue();
    }

    @Test
    @DisplayName("미수신(키 없음)·손상된 값·Redis 장애는 COLLECTOR_UNAVAILABLE")
    void unavailable() {
        given(hash.entries("demo:price:005930")).willReturn(Map.of(), Map.of("price", "bad"))
                .willThrow(new IllegalStateException("redis down"));

        for (int i = 0; i < 3; i++) {
            assertError(() -> service.getPrice("005930"), ErrorCode.COLLECTOR_UNAVAILABLE);
        }
    }

    @Test
    @DisplayName("설정하지 않은 종목은 MARKET_NOT_FOUND이고 Redis·DB를 읽지 않는다")
    void unsupportedCode() {
        assertError(() -> service.getPrice("000660"), ErrorCode.MARKET_NOT_FOUND);
        assertError(() -> service.getPrice("KRW-BTC"), ErrorCode.MARKET_NOT_FOUND);
        assertError(() -> service.getCandles("000660", "1m", null, null, 10), ErrorCode.MARKET_NOT_FOUND);
        verifyNoInteractions(hash, candles);
    }

    @Test
    @DisplayName("캔들: 저장된 1분봉을 응답으로 바꾸고 to 기본값은 지금")
    void readsCandles() {
        MinuteCandle candle = new MinuteCandle("005930", Instant.parse("2026-10-26T00:00:00Z"),
                new BigDecimal("71000"), new BigDecimal("71300"), new BigDecimal("70900"), new BigDecimal("71200"),
                new BigDecimal("1500"));
        given(candles.findMinuteCandles(eq("005930"), eq(null), eq(NOW), eq(200))).willReturn(List.of(candle));

        var result = service.getCandles("005930", "1m", null, null, 200);

        assertThat(result.getSymbol()).isEqualTo("005930");
        assertThat(result.getInterval()).isEqualTo("1m");
        assertThat(result.getCandles()).hasSize(1);
        assertThat(result.getCandles().get(0).getClose()).isEqualByComparingTo("71200");
    }

    @Test
    @DisplayName("캔들: interval≠1m, limit 범위 밖, from>to는 INVALID_REQUEST")
    void invalidCandleRequests() {
        assertError(() -> service.getCandles("005930", "5m", null, null, 10), ErrorCode.INVALID_REQUEST);
        assertError(() -> service.getCandles("005930", "1m", null, null, 0), ErrorCode.INVALID_REQUEST);
        assertError(() -> service.getCandles("005930", "1m", null, null, 501), ErrorCode.INVALID_REQUEST);
        assertError(() -> service.getCandles("005930", "1m", NOW, NOW.minusSeconds(1), 10), ErrorCode.INVALID_REQUEST);
        verifyNoInteractions(candles);
    }

    @Test
    @DisplayName("캔들: DB 오류는 COLLECTOR_UNAVAILABLE")
    void candleDbFailure() {
        given(candles.findMinuteCandles(any(), any(), any(), anyInt())).willThrow(new IllegalStateException("db down"));

        assertError(() -> service.getCandles("005930", "1m", null, null, 10), ErrorCode.COLLECTOR_UNAVAILABLE);
    }
}
