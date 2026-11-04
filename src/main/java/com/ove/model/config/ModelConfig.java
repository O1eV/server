package com.ove.model.config;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.PatternTopic;
import org.springframework.data.redis.listener.Topic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

import com.ove.market.collector.CandleRepository;
import com.ove.market.collector.CollectorKeys;
import com.ove.market.collector.CollectorProperties;
import com.ove.market.collector.kiwoom.KiwoomCollectorConfig;
import com.ove.market.collector.kiwoom.KiwoomProperties;
import com.ove.model.client.ModelClient;
import com.ove.model.service.CandleClosedListener;
import com.ove.model.service.SignalGenerationService;
import com.ove.realtime.service.RealtimePublisher;
import com.ove.signal.repository.SignalRepository;

import tools.jackson.databind.ObjectMapper;

/**
 * AI 모델 신호 생성 조립. model.enabled=true이고 collector.enabled=true(캔들 DB·마감 알림이 있을 때)일 때만 적용되어,
 * 기본값에서는 모델 서버에 연결하지도 Redis 채널을 듣지도 않는다. 코인(업비트) 마감봉을 구독하고,
 * 키움 수집기가 켜져 있으면(collector.kiwoom.enabled) demo: 접두사 채널의 주식 마감봉도 함께 구독한다.
 */
@Configuration
@ConditionalOnProperty(name = {"model.enabled", "collector.enabled"}, havingValue = "true")
@EnableConfigurationProperties(ModelProperties.class)
public class ModelConfig {

    @Bean
    public ModelClient modelClient(ModelProperties properties) {
        properties.validate();
        return new ModelClient(properties);
    }

    @Bean
    public SignalGenerationService signalGenerationService(ModelProperties properties, CandleRepository candleRepository,
                                                           ModelClient modelClient, SignalRepository signalRepository,
                                                           RealtimePublisher publisher) {
        return new SignalGenerationService(properties, candleRepository, modelClient, signalRepository, publisher);
    }

    // 한 번에 하나씩, 밀리면 오래된 요청부터 버린다(신호는 다음 봉에서 다시 만들어진다).
    @Bean(destroyMethod = "shutdownNow")
    public ThreadPoolExecutor modelSignalExecutor() {
        return new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(30),
                new ThreadPoolExecutor.DiscardOldestPolicy());
    }

    @Bean
    public RedisMessageListenerContainer modelSignalContainer(RedisConnectionFactory connectionFactory,
                                                              SignalGenerationService service, ObjectMapper objectMapper,
                                                              ThreadPoolExecutor modelSignalExecutor,
                                                              CollectorProperties collectorProperties,
                                                              ObjectProvider<KiwoomProperties> kiwoomProperties) {
        // KiwoomProperties 빈은 키움 수집기가 켜졌을 때만 있어서, 꺼져 있으면(null) 코인만 듣는다.
        KiwoomProperties kiwoom = kiwoomProperties.getIfAvailable();
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        container.addMessageListener(new CandleClosedListener(service, objectMapper, modelSignalExecutor,
                listenerSymbols(collectorProperties, kiwoom)), listenerTopics(kiwoom));
        return container;
    }

    // 신호를 만들 종목: 코인 + (키움이 켜져 있으면) 주식 시연 종목
    static List<String> listenerSymbols(CollectorProperties collectorProperties, KiwoomProperties kiwoom) {
        List<String> symbols = new ArrayList<>(collectorProperties.upperCaseSymbols());
        if (kiwoom != null && kiwoom.enabled()) {
            symbols.addAll(kiwoom.normalizedSymbols());
        }
        return symbols;
    }

    // 마감봉 채널: 코인은 접두사 없이, 키움은 demo: 접두사로 발행된다(MarketRedisPublisher의 keyPrefix와 같은 값)
    static List<Topic> listenerTopics(KiwoomProperties kiwoom) {
        List<Topic> topics = new ArrayList<>();
        topics.add(new PatternTopic(CollectorKeys.candleClosedChannel("*")));
        if (kiwoom != null && kiwoom.enabled()) {
            topics.add(new PatternTopic(KiwoomCollectorConfig.DEMO_KEY_PREFIX + CollectorKeys.candleClosedChannel("*")));
        }
        return topics;
    }
}
