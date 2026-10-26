package com.ove.market.service;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.Environment;
import com.ove.market.collector.CollectorProperties;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import com.ove.global.exception.BusinessException;
import com.ove.global.exception.ErrorCode;
import com.ove.market.collector.CollectorKeys;
import com.ove.market.dto.CollectedPriceResponse;

@Service
public class CollectedPriceService {
    private final StringRedisTemplate redis;
    private final boolean enabled;
    private final List<String> symbols;
    private final Clock clock;

    @Autowired
    public CollectedPriceService(StringRedisTemplate redis, Environment environment) {
        this(redis, Binder.get(environment).bindOrCreate("collector", CollectorProperties.class));
    }

    private CollectedPriceService(StringRedisTemplate redis, CollectorProperties properties) {
        this(redis, properties.enabled(), properties.upperCaseSymbols(), Clock.systemUTC());
    }

    CollectedPriceService(StringRedisTemplate redis, boolean enabled, List<String> symbols, Clock clock) {
        this.redis = redis;
        this.enabled = enabled;
        this.symbols = symbols.stream().map(value -> value.trim().toUpperCase(Locale.ROOT)).toList();
        this.clock = clock;
    }

    public CollectedPriceResponse getPrice(String symbol) {
        String normalized = symbol.trim().toUpperCase(Locale.ROOT);
        MarketService.requireSupportedSymbol(normalized);
        if (!enabled || !symbols.contains(normalized)) {
            throw new BusinessException(ErrorCode.COLLECTOR_UNAVAILABLE);
        }
        try {
            Map<Object, Object> values = redis.opsForHash().entries(CollectorKeys.priceKey(normalized));
            if (values.isEmpty()) throw new BusinessException(ErrorCode.COLLECTOR_UNAVAILABLE);
            BigDecimal price = new BigDecimal(values.get("price").toString());
            Instant tradeAt = Instant.parse(values.get("tradeTimestamp").toString());
            Instant updatedAt = Instant.parse(values.get("updatedAt").toString());
            if (price.signum() <= 0 || updatedAt.isAfter(clock.instant().plusSeconds(5))) {
                throw new BusinessException(ErrorCode.COLLECTOR_UNAVAILABLE);
            }
            boolean stale = Duration.between(updatedAt, clock.instant()).compareTo(Duration.ofSeconds(10)) > 0;
            return new CollectedPriceResponse(normalized, price, tradeAt, updatedAt, stale);
        } catch (BusinessException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            // Redis 장애·불완전한 캐시를 일반 500 대신 명확한 수집 상태로 전달한다.
            throw new BusinessException(ErrorCode.COLLECTOR_UNAVAILABLE);
        }
    }
}
