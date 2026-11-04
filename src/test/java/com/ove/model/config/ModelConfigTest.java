package com.ove.model.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

import com.ove.market.collector.CandleRepository;
import com.ove.market.collector.CollectorProperties;
import com.ove.market.collector.kiwoom.KiwoomProperties;
import com.ove.model.client.ModelClient;
import com.ove.model.service.SignalGenerationService;
import com.ove.realtime.service.RealtimePublisher;
import com.ove.signal.repository.SignalRepository;

import tools.jackson.databind.ObjectMapper;

/** model.enabled·collector.enabled 조합에 따른 ModelConfig 조립과 기동 시 설정 검증. */
class ModelConfigTest {

    ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(ModelConfig.class)
            .withBean(CandleRepository.class, () -> mock(CandleRepository.class))
            .withBean(SignalRepository.class, () -> mock(SignalRepository.class))
            .withBean(RealtimePublisher.class, () -> mock(RealtimePublisher.class))
            .withBean(RedisConnectionFactory.class, () -> mock(RedisConnectionFactory.class))
            .withBean(ObjectMapper.class, ObjectMapper::new)
            // 조립만 확인하므로 가짜 연결 팩토리로 Redis 구독을 시작하지 않게 한다.
            .withBean(BeanPostProcessor.class, () -> new BeanPostProcessor() {
                @Override
                public Object postProcessBeforeInitialization(Object bean, String beanName) {
                    if (bean instanceof RedisMessageListenerContainer container) {
                        container.setAutoStartup(false);
                    }
                    return bean;
                }
            })
            .withBean(CollectorProperties.class, () -> {
                CollectorProperties properties = mock(CollectorProperties.class);
                org.mockito.BDDMockito.given(properties.upperCaseSymbols()).willReturn(List.of("KRW-BTC"));
                return properties;
            });

    @Test
    @DisplayName("model.enabled와 collector.enabled가 모두 true면 빈이 조립된다")
    void assembledWhenBothEnabled() {
        runner.withPropertyValues("model.enabled=true", "collector.enabled=true", "model.base-url=http://localhost:9")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(ModelClient.class);
                    assertThat(context).hasSingleBean(SignalGenerationService.class);
                });
    }

    @Test
    @DisplayName("키움 수집기가 켜져 있으면 주식 종목과 demo: 마감봉 채널도 듣고, 꺼져 있으면 코인만 듣는다")
    void mergesKiwoomSymbolsAndChannel() {
        CollectorProperties collector = mock(CollectorProperties.class);
        org.mockito.BDDMockito.given(collector.upperCaseSymbols()).willReturn(List.of("KRW-BTC"));
        KiwoomProperties on = kiwoom(true, List.of("005930", "000660"));
        KiwoomProperties off = kiwoom(false, List.of("005930"));

        assertThat(ModelConfig.listenerSymbols(collector, on)).containsExactly("KRW-BTC", "005930", "000660");
        assertThat(ModelConfig.listenerTopics(on)).extracting(topic -> topic.getTopic())
                .containsExactly("candles.closed:*", "demo:candles.closed:*");
        assertThat(ModelConfig.listenerSymbols(collector, off)).containsExactly("KRW-BTC");
        assertThat(ModelConfig.listenerSymbols(collector, null)).containsExactly("KRW-BTC");
        assertThat(ModelConfig.listenerTopics(off)).extracting(topic -> topic.getTopic())
                .containsExactly("candles.closed:*");
        assertThat(ModelConfig.listenerTopics(null)).hasSize(1);
    }

    @Test
    @DisplayName("키움 설정 빈이 있어도 조립된다")
    void assembledWithKiwoomProperties() {
        runner.withBean(KiwoomProperties.class, () -> kiwoom(true, List.of("005930")))
                .withPropertyValues("model.enabled=true", "collector.enabled=true", "model.base-url=http://localhost:9")
                .run(context -> assertThat(context).hasNotFailed());
    }

    private static KiwoomProperties kiwoom(boolean enabled, List<String> symbols) {
        return new KiwoomProperties(enabled, symbols, null, null, "https://api.kiwoom.com",
                "wss://api.kiwoom.com:10000/api/dostk/websocket", java.time.Duration.ofMinutes(10),
                java.time.Duration.ofSeconds(120), java.time.Duration.ofSeconds(15), java.time.Duration.ofMillis(250),
                java.time.Duration.ofMillis(500), java.time.Duration.ofSeconds(3), false, null, "1");
    }

    @Test
    @DisplayName("collector.enabled가 꺼져 있으면 model.enabled=true여도 조립되지 않는다")
    void notAssembledWithoutCollector() {
        runner.withPropertyValues("model.enabled=true", "model.base-url=http://localhost:9").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(ModelClient.class);
        });
    }

    @Test
    @DisplayName("base-url이 없으면 기동이 막힌다")
    void failsWithoutBaseUrl() {
        runner.withPropertyValues("model.enabled=true", "collector.enabled=true").run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasRootCauseInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("model.base-url");
        });
    }

    @Test
    @DisplayName("candle-count가 15 미만이면 기동이 막힌다")
    void failsWithTooFewCandleCount() {
        runner.withPropertyValues("model.enabled=true", "collector.enabled=true", "model.base-url=http://localhost:9",
                "model.candle-count=14").run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).hasMessageContaining("candle-count");
                });
    }
}
