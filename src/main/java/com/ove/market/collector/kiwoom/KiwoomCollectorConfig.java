package com.ove.market.collector.kiwoom;

import java.time.Clock;
import java.util.random.RandomGenerator;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.ove.market.collector.CandleAggregator;
import com.ove.market.collector.CandleRepository;
import com.ove.market.collector.CollectorProperties;
import com.ove.market.collector.MarketCollector;
import com.ove.market.collector.MarketRedisPublisher;
import com.ove.market.collector.ReconnectBackoff;

import tools.jackson.databind.ObjectMapper;

/**
 * 키움증권(주식) 시세 수집기 조립. collector.kiwoom.enabled=true일 때만 적용되어서
 * 기본값(false)에서는 키움에 연결하지도, 토큰을 발급받지도 않는다. 시세 조회 API만 쓴다.
 */
@Configuration
@ConditionalOnProperty(prefix = "collector.kiwoom", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(KiwoomProperties.class)
public class KiwoomCollectorConfig {

    @Bean
    public KiwoomRestClient kiwoomRestClient(KiwoomProperties properties, ObjectMapper objectMapper) {
        // 켜져 있는데 설정이 잘못되면(키 없음, 종목 코드 오류 등) 여기서 바로 기동을 막는다
        properties.validate();
        return new KiwoomRestClient(properties.restBaseUrl(), properties.restConnectTimeout(),
                properties.restReadTimeout(), properties.restMinInterval(), objectMapper);
    }

    // 종료할 때 토큰을 폐기한다(수집기를 먼저 멈춘 뒤 폐기되도록 수집기가 이 빈에 의존한다)
    @Bean(destroyMethod = "close")
    public KiwoomTokenProvider kiwoomTokenProvider(KiwoomRestClient restClient, KiwoomProperties properties,
                                                   Clock clock) {
        return new KiwoomTokenProvider(restClient, properties, clock);
    }

    @Bean
    public KiwoomTradeSource kiwoomTradeSource(KiwoomProperties properties, CollectorProperties collectorProperties,
                                               KiwoomTokenProvider tokenProvider, ObjectMapper objectMapper,
                                               Clock clock) {
        ReconnectBackoff backoff = new ReconnectBackoff(collectorProperties.reconnectInitialDelay(),
                collectorProperties.reconnectMaxDelay(), collectorProperties.reconnectMinDelay(),
                RandomGenerator.getDefault());
        return new KiwoomTradeSource(properties, collectorProperties, tokenProvider, objectMapper, backoff, clock);
    }

    // 분봉 보충은 기본으로 끈다(minute-bar-label 확인 전). 켜면 이 빈이 만들어진다.
    @Bean
    @ConditionalOnProperty(prefix = "collector.kiwoom", name = "backfill-enabled", havingValue = "true")
    public KiwoomChartBackfillSource kiwoomChartBackfillSource(KiwoomRestClient restClient,
                                                               KiwoomTokenProvider tokenProvider,
                                                               KiwoomProperties properties) {
        return new KiwoomChartBackfillSource(restClient, tokenProvider, properties);
    }

    @Bean(initMethod = "start", destroyMethod = "stop")
    public MarketCollector kiwoomMarketCollector(KiwoomProperties properties, CollectorProperties collectorProperties,
                                                 KiwoomTradeSource tradeSource, CandleRepository candleRepository,
                                                 MarketRedisPublisher redisPublisher,
                                                 ObjectProvider<KiwoomChartBackfillSource> backfillSource,
                                                 Clock clock) {
        return new MarketCollector("키움", properties.normalizedSymbols(), collectorProperties, tradeSource,
                new CandleAggregator(collectorProperties.graceDelay()), candleRepository, redisPublisher,
                backfillSource.getIfAvailable(), clock);
    }
}
