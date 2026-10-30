package com.ove.market.collector.kiwoom;

import java.time.Clock;
import java.util.random.RandomGenerator;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.data.redis.core.StringRedisTemplate;

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

    // 키움 시세는 개인 키로 시연하는 용도라(약관상 제3자 제공 금지) 배포(prod) 환경에서는 켤 수 없다.
    // 조용히 무시하면 켰다고 착각할 수 있어서, prod 프로필에서 켜져 있으면 기동을 막는다.
    // 키움(주식 시연) 시세의 Redis 키·채널 접두사
    public static final String DEMO_KEY_PREFIX = "demo:";

    static final String FORBIDDEN_PROFILE = "prod";

    static void requireNotProd(Environment environment) {
        if (environment.acceptsProfiles(Profiles.of(FORBIDDEN_PROFILE))) {
            throw new IllegalStateException("키움 시세 수집기(collector.kiwoom.enabled=true)는 " + FORBIDDEN_PROFILE
                    + " 프로필에서 켤 수 없습니다. 개인 키로 하는 시연 전용이며 키움 약관상 시세를 제3자에게 제공할 수 없습니다.");
        }
    }

    @Bean
    public KiwoomRestClient kiwoomRestClient(KiwoomProperties properties, ObjectMapper objectMapper,
                                             Environment environment) {
        requireNotProd(environment);
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
                                                 StringRedisTemplate redisTemplate, ObjectMapper objectMapper,
                                                 ObjectProvider<KiwoomChartBackfillSource> backfillSource,
                                                 Clock clock) {
        // 주식(시연용) 시세는 키 이름 앞에 demo:를 붙여 코인 시세와 Redis 키·채널이 섞이지 않게 한다.
        // 나중에 Redis 시세를 STOMP로 중계할 때 코인 키만 구독하면 주식 시세가 사용자에게 나가지 않는다.
        // 업비트용 공용 MarketRedisPublisher 빈과 타입이 겹치지 않도록 빈으로 등록하지 않고 여기서 직접 만든다.
        MarketRedisPublisher redisPublisher = new MarketRedisPublisher(redisTemplate, objectMapper,
                collectorProperties.priceTtl(), collectorProperties.tickPublishInterval(), DEMO_KEY_PREFIX, true);
        return new MarketCollector("키움", properties.normalizedSymbols(), collectorProperties, tradeSource,
                new CandleAggregator(collectorProperties.graceDelay()), candleRepository, redisPublisher,
                backfillSource.getIfAvailable(), clock);
    }
}
