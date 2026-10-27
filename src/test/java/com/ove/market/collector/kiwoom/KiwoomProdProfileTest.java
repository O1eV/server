package com.ove.market.collector.kiwoom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.mock.env.MockEnvironment;

import tools.jackson.databind.ObjectMapper;

/**
 * 키움 시세 수집기는 개인 키 시연 전용이라 prod 프로필에서는 켤 수 없다.
 */
class KiwoomProdProfileTest {

    @Test
    @DisplayName("prod 프로필이면 거부하고, 그 밖의 프로필(dev, 없음)은 통과한다")
    void blocksOnlyProd() {
        MockEnvironment prod = new MockEnvironment();
        prod.setActiveProfiles("prod");
        assertThatThrownBy(() -> KiwoomCollectorConfig.requireNotProd(prod))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("prod").hasMessageContaining("제3자");

        MockEnvironment dev = new MockEnvironment();
        dev.setActiveProfiles("dev");
        assertThatCode(() -> KiwoomCollectorConfig.requireNotProd(dev)).doesNotThrowAnyException();
        assertThatCode(() -> KiwoomCollectorConfig.requireNotProd(new MockEnvironment())).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("prod 프로필에서 collector.kiwoom.enabled=true로 기동하면 컨텍스트가 실패한다 (키움에 연결하기 전에)")
    void contextFailsOnProd() {
        new ApplicationContextRunner()
                .withUserConfiguration(KiwoomCollectorConfig.class)
                .withBean(ObjectMapper.class, ObjectMapper::new)
                .withPropertyValues("spring.profiles.active=prod", "collector.kiwoom.enabled=true",
                        "collector.kiwoom.app-key=TEST-APP-KEY-not-real",
                        "collector.kiwoom.app-secret=TEST-APP-SECRET-not-real")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).rootCause().hasMessageContaining("prod 프로필에서 켤 수 없습니다");
                });
    }
}
