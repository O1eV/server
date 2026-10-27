package com.ove.market.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.*;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import com.ove.global.exception.BusinessException;
import com.ove.global.exception.ErrorCode;

class CollectedPriceServiceTest {
    StringRedisTemplate redis;
    HashOperations<String, Object, Object> hash;
    CollectedPriceService service;
    Clock clock = Clock.fixed(Instant.parse("2026-10-26T00:00:10Z"), ZoneOffset.UTC);

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        hash = mock(HashOperations.class);
        given(redis.opsForHash()).willReturn(hash);
        service = new CollectedPriceService(redis, true, List.of("KRW-BTC"), clock);
    }
    Map<Object, Object> price(String updatedAt) {
        return Map.of("price", "98500000.25", "tradeTimestamp", updatedAt, "updatedAt", updatedAt);
    }
    @Test void 수집기가_쓴_해시를_조회한다() {
        given(hash.entries("price:KRW-BTC")).willReturn(price("2026-10-26T00:00:09Z"));
        var result = service.getPrice("krw-btc");
        assertThat(result.symbol()).isEqualTo("KRW-BTC");
        assertThat(result.price()).isEqualByComparingTo("98500000.25");
        assertThat(result.stale()).isFalse();
    }
    @Test void 오래된_해시는_stale() {
        given(hash.entries("price:KRW-BTC")).willReturn(price("2026-10-25T23:59:59Z"));
        assertThat(service.getPrice("KRW-BTC").stale()).isTrue();
    }
    @Test void 미수신과_손상된_캐시는_503() {
        given(hash.entries("price:KRW-BTC")).willReturn(Map.of(), Map.of("price", "bad"));
        for (int i = 0; i < 2; i++) {
            assertThatThrownBy(() -> service.getPrice("KRW-BTC"))
                .isInstanceOf(BusinessException.class).extracting("errorCode").isEqualTo(ErrorCode.COLLECTOR_UNAVAILABLE);
        }
    }
    @Test void 비활성은_남은_캐시를_제공하지_않는다() {
        var disabled = new CollectedPriceService(redis, false, List.of("KRW-BTC"), clock);
        assertThatThrownBy(() -> disabled.getPrice("KRW-BTC"))
            .isInstanceOf(BusinessException.class).extracting("errorCode").isEqualTo(ErrorCode.COLLECTOR_UNAVAILABLE);
        verify(hash, never()).entries(anyString());
    }
    @Test void 미지원_심볼은_404() {
        assertThatThrownBy(() -> service.getPrice("UNKNOWN"))
            .isInstanceOf(BusinessException.class).extracting("errorCode").isEqualTo(ErrorCode.MARKET_NOT_FOUND);
    }
    @Test void 수집하지_않는_종목은_503() {
        assertThatThrownBy(() -> service.getPrice("KRW-ETH"))
            .isInstanceOf(BusinessException.class).extracting("errorCode").isEqualTo(ErrorCode.COLLECTOR_UNAVAILABLE);
    }
    @Test void Redis_장애는_503() {
        given(hash.entries("price:KRW-BTC")).willThrow(new org.springframework.data.redis.RedisConnectionFailureException("offline"));
        assertThatThrownBy(() -> service.getPrice("KRW-BTC"))
            .isInstanceOf(BusinessException.class).extracting("errorCode").isEqualTo(ErrorCode.COLLECTOR_UNAVAILABLE);
    }
}
