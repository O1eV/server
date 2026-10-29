package com.ove.market.service;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import com.ove.global.exception.BusinessException;
import com.ove.global.exception.ErrorCode;
import com.ove.market.collector.CandleRepository;
import com.ove.market.collector.kiwoom.KiwoomCollectorConfig;
import com.ove.market.collector.kiwoom.KiwoomProperties;
import com.ove.market.dto.CandleListResponse;
import com.ove.market.dto.CandleResponse;
import com.ove.market.dto.CollectedPriceResponse;

/**
 * 발표용 주식 시세 조회. 키움 수집기가 저장한 Redis(demo:price:{code})와 candles 테이블을 읽기만 하고
 * 키움이나 다른 외부에는 호출하지 않는다. 수집기가 켜져 있고 prod가 아닐 때만 빈이 만들어진다.
 */
@Service
@ConditionalOnProperty(prefix = "collector.kiwoom", name = "enabled", havingValue = "true")
@Profile("!prod")
public class DemoStockService {

    private static final Duration STALE_AFTER = Duration.ofSeconds(10);
    private static final int MAX_CANDLES = 500;
    private static final String SUPPORTED_INTERVAL = "1m";

    private final StringRedisTemplate redis;
    private final List<String> symbols;
    private final CandleRepository candleRepository;
    private final Clock clock;

    @Autowired
    public DemoStockService(StringRedisTemplate redis, KiwoomProperties properties,
                            CandleRepository candleRepository, Clock clock) {
        this(redis, properties.normalizedSymbols(), candleRepository, clock);
    }

    DemoStockService(StringRedisTemplate redis, List<String> symbols, CandleRepository candleRepository,
                     Clock clock) {
        this.redis = redis;
        this.symbols = symbols;
        this.candleRepository = candleRepository;
        this.clock = clock;
    }

    // 수집된 최신 현재가. 키가 없으면(장 마감으로 만료, 미수신) 503
    public CollectedPriceResponse getPrice(String code) {
        String symbol = requireConfigured(code);
        try {
            Map<Object, Object> values = redis.opsForHash()
                    .entries(KiwoomCollectorConfig.DEMO_KEY_PREFIX + "price:" + symbol);
            if (values.isEmpty()) {
                throw new BusinessException(ErrorCode.COLLECTOR_UNAVAILABLE);
            }
            BigDecimal price = new BigDecimal(values.get("price").toString());
            Instant tradeAt = Instant.parse(values.get("tradeTimestamp").toString());
            Instant updatedAt = Instant.parse(values.get("updatedAt").toString());
            if (price.signum() <= 0 || updatedAt.isAfter(clock.instant().plusSeconds(5))) {
                throw new BusinessException(ErrorCode.COLLECTOR_UNAVAILABLE);
            }
            boolean stale = Duration.between(updatedAt, clock.instant()).compareTo(STALE_AFTER) > 0;
            return new CollectedPriceResponse(symbol, price, tradeAt, updatedAt, stale);
        } catch (BusinessException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            // Redis 장애·불완전한 캐시는 500 대신 수집 상태 오류로 알린다.
            throw new BusinessException(ErrorCode.COLLECTOR_UNAVAILABLE);
        }
    }

    // 저장된 1분봉(시간 오름차순). 저장하는 단위가 1분봉뿐이라 interval은 1m만 받는다.
    public CandleListResponse getCandles(String code, String interval, Instant from, Instant to, int limit) {
        String symbol = requireConfigured(code);
        if (!SUPPORTED_INTERVAL.equals(interval)) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "주식 시연 캔들은 interval=1m만 지원합니다.");
        }
        if (limit < 1 || limit > MAX_CANDLES) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "limit은 1~500 사이여야 합니다.");
        }
        if (from != null && to != null && from.isAfter(to)) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "from은 to보다 늦을 수 없습니다.");
        }
        try {
            List<CandleResponse> candles = candleRepository
                    .findMinuteCandles(symbol, from, to != null ? to : clock.instant(), limit)
                    .stream().map(CandleResponse::new).toList();
            return new CandleListResponse(symbol, interval, candles);
        } catch (RuntimeException exception) {
            throw new BusinessException(ErrorCode.COLLECTOR_UNAVAILABLE);
        }
    }

    public boolean supportsSymbol(String symbol) {
        return symbols.contains(symbol);
    }

    // KIWOOM_SYMBOLS에 설정된 종목만 허용한다.
    private String requireConfigured(String code) {
        String symbol = code == null ? "" : code.trim().toUpperCase(java.util.Locale.ROOT);
        if (!symbols.contains(symbol)) {
            throw new BusinessException(ErrorCode.MARKET_NOT_FOUND);
        }
        return symbol;
    }
}
