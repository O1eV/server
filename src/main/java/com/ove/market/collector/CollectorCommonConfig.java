package com.ove.market.collector;

import java.time.Clock;

import org.springframework.boot.autoconfigure.condition.AnyNestedCondition;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

import tools.jackson.databind.ObjectMapper;

/**
 * 업비트·키움 수집기가 같이 쓰는 빈. 둘 중 하나라도 켜졌을 때만 만들어져서,
 * 둘 다 꺼진 기본 상태에서는 아무것도 만들지 않는다.
 */
@Configuration
@Conditional(CollectorCommonConfig.AnyCollectorEnabled.class)
@EnableConfigurationProperties(CollectorProperties.class)
public class CollectorCommonConfig {

    /**
     * 업비트 또는 키움 수집기 중 하나라도 true로 켜졌는지 본다. SpEL로 쓰면 COLLECTOR_ENABLED=yes나 빈 값처럼
     * 불리언이 아닌 값에서 기동이 실패하지만, @ConditionalOnProperty는 "true"가 아니면 그냥 꺼진 것으로 본다(이전 동작과 같다).
     */
    static class AnyCollectorEnabled extends AnyNestedCondition {

        AnyCollectorEnabled() {
            super(ConfigurationPhase.PARSE_CONFIGURATION);
        }

        @ConditionalOnProperty(prefix = "collector", name = "enabled", havingValue = "true")
        static class UpbitEnabled {
        }

        @ConditionalOnProperty(prefix = "collector.kiwoom", name = "enabled", havingValue = "true")
        static class KiwoomEnabled {
        }
    }

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
