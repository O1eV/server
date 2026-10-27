package com.ove.market.collector;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.time.Clock;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

import tools.jackson.databind.ObjectMapper;

/**
 * 공용 수집기 설정의 켜짐 조건: 둘 중 하나가 "true"일 때만 켜지고, true가 아닌 값(yes, 빈 값, false)이면 기동 실패 없이 꺼진다.
 */
class CollectorCommonConfigConditionTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(CollectorCommonConfig.class)
            .withBean(JdbcTemplate.class, () -> mock(JdbcTemplate.class))
            .withBean(StringRedisTemplate.class, () -> mock(StringRedisTemplate.class))
            .withBean(ObjectMapper.class, ObjectMapper::new);

    @Test
    @DisplayName("둘 다 설정이 없으면 꺼져 있고 아무 빈도 만들지 않는다")
    void disabledByDefault() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(Clock.class);
            assertThat(context).doesNotHaveBean(CandleRepository.class);
        });
    }

    @Test
    @DisplayName("collector.enabled 또는 collector.kiwoom.enabled가 true이면 켜진다")
    void enabledWhenEitherIsTrue() {
        runner.withPropertyValues("collector.enabled=true").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(CandleRepository.class);
        });
        runner.withPropertyValues("collector.kiwoom.enabled=true").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(CandleRepository.class);
        });
        runner.withPropertyValues("collector.enabled=false", "collector.kiwoom.enabled=TRUE").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(CandleRepository.class);
        });
    }

    @Test
    @DisplayName("COLLECTOR_ENABLED=yes, 빈 값, false 같은 값은 기동에 실패하지 않고 꺼진 것으로 본다")
    void nonBooleanValuesMeanDisabled() {
        for (String value : new String[] {"yes", "", "false", "1", "on"}) {
            runner.withPropertyValues("collector.enabled=" + value, "collector.kiwoom.enabled=" + value).run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(context).doesNotHaveBean(CandleRepository.class);
            });
        }
    }
}
