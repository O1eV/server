package com.ove.market.collector;

import java.time.Clock;
import java.util.random.RandomGenerator;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

import com.ove.market.client.UpbitClient;

import tools.jackson.databind.ObjectMapper;

/**
 * 시세 수집기 조립. collector.enabled=true일 때만 이 설정이 적용되어서,
 * 로컬·테스트 기본값(false)에서는 업비트에 연결하지도, candles 테이블을 만들지도 않는다.
 */
@Configuration
@ConditionalOnProperty(prefix = "collector", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(CollectorProperties.class)
public class CollectorConfig {

    // 시간에 의존하는 동작을 테스트에서 제어할 수 있도록 빈으로 둔다(테스트는 @Primary Clock으로 대체한다).
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    public CandleRepository candleRepository(JdbcTemplate jdbcTemplate) {
        return new CandleRepository(jdbcTemplate);
    }

    @Bean
    public MarketRedisPublisher marketRedisPublisher(StringRedisTemplate redisTemplate, ObjectMapper objectMapper,
                                                     CollectorProperties properties) {
        return new MarketRedisPublisher(redisTemplate, objectMapper, properties.priceTtl(),
                properties.tickPublishInterval());
    }

    @Bean
    public TradeSource tradeSource(CollectorProperties properties, ObjectMapper objectMapper, Clock clock) {
        ReconnectBackoff backoff = new ReconnectBackoff(properties.reconnectInitialDelay(),
                properties.reconnectMaxDelay(), properties.reconnectMinDelay(), RandomGenerator.getDefault());
        return new UpbitTradeSource(properties, objectMapper, backoff, clock);
    }

    @Bean(initMethod = "start", destroyMethod = "stop")
    public MarketCollector marketCollector(CollectorProperties properties, TradeSource tradeSource,
                                           CandleRepository candleRepository, MarketRedisPublisher redisPublisher,
                                           UpbitClient upbitClient, Clock clock) {
        return new MarketCollector(properties, tradeSource, new CandleAggregator(properties.graceDelay()),
                candleRepository, redisPublisher, upbitClient, clock);
    }
}
