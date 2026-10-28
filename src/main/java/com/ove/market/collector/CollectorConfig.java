package com.ove.market.collector;

import java.time.Clock;
import java.util.random.RandomGenerator;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.ove.market.client.UpbitClient;

import tools.jackson.databind.ObjectMapper;

/**
 * 업비트(코인) 시세 수집기 조립. collector.enabled=true일 때만 이 설정이 적용되어서,
 * 로컬·테스트 기본값(false)에서는 업비트에 연결하지도, candles 테이블을 만들지도 않는다.
 * (candleRepository, marketRedisPublisher, clock 등 공용 빈은 CollectorCommonConfig에 있다.)
 */
@Configuration
@ConditionalOnProperty(prefix = "collector", name = "enabled", havingValue = "true")
public class CollectorConfig {

    @Bean
    public UpbitTradeSource upbitTradeSource(CollectorProperties properties, ObjectMapper objectMapper, Clock clock) {
        ReconnectBackoff backoff = new ReconnectBackoff(properties.reconnectInitialDelay(),
                properties.reconnectMaxDelay(), properties.reconnectMinDelay(), RandomGenerator.getDefault());
        return new UpbitTradeSource(properties, objectMapper, backoff, clock);
    }

    @Bean(initMethod = "start", destroyMethod = "stop")
    public MarketCollector marketCollector(CollectorProperties properties, UpbitTradeSource tradeSource,
                                           CandleRepository candleRepository, MarketRedisPublisher redisPublisher,
                                           UpbitClient upbitClient, Clock clock) {
        return new MarketCollector("업비트", properties.upperCaseSymbols(), properties, tradeSource,
                new CandleAggregator(properties.graceDelay()), candleRepository, redisPublisher,
                new UpbitCandleBackfillSource(upbitClient), clock);
    }
}
