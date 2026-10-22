package com.ove.market.collector;

import java.time.Clock;

import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

import tools.jackson.databind.ObjectMapper;

/**
 * 업비트·키움 수집기가 같이 쓰는 빈. 둘 중 하나라도 켜졌을 때만 만들어져서,
 * 둘 다 꺼진 기본 상태에서는 아무것도 만들지 않는다.
 */
@Configuration
@ConditionalOnExpression("${collector.enabled:false} or ${collector.kiwoom.enabled:false}")
@EnableConfigurationProperties(CollectorProperties.class)
public class CollectorCommonConfig {

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
}
