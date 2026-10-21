package com.ove.market.collector;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.random.RandomGenerator;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ReconnectBackoffTest {

    private static final Duration INITIAL = Duration.ofSeconds(1);
    private static final Duration MAX = Duration.ofSeconds(30);
    private static final Duration MIN = Duration.ofMillis(250);

    // nextDouble()이 항상 같은 값을 주는 난수 (0 = 지터 최소, 0.999.. = 지터 최대)
    private RandomGenerator fixed(double value) {
        return new RandomGenerator() {
            @Override
            public long nextLong() {
                return 0;
            }

            @Override
            public double nextDouble() {
                return value;
            }
        };
    }

    @Test
    @DisplayName("지터가 최대일 때 1s → 2s → 4s → 8s → 16s → 30s(상한)으로 늘어난다")
    void growsExponentiallyUpToMax() {
        ReconnectBackoff backoff = new ReconnectBackoff(INITIAL, MAX, MIN, fixed(1.0));

        assertThat(backoff.nextDelay()).isEqualTo(Duration.ofMillis(1000));
        assertThat(backoff.nextDelay()).isEqualTo(Duration.ofMillis(2000));
        assertThat(backoff.nextDelay()).isEqualTo(Duration.ofMillis(4000));
        assertThat(backoff.nextDelay()).isEqualTo(Duration.ofMillis(8000));
        assertThat(backoff.nextDelay()).isEqualTo(Duration.ofMillis(16000));
        assertThat(backoff.nextDelay()).isEqualTo(Duration.ofMillis(30000));
        assertThat(backoff.nextDelay()).isEqualTo(Duration.ofMillis(30000));
    }

    @Test
    @DisplayName("지터가 최소일 때는 계산된 대기 시간의 절반이다")
    void jitterRange() {
        ReconnectBackoff backoff = new ReconnectBackoff(INITIAL, MAX, MIN, fixed(0.0));

        assertThat(backoff.nextDelay()).isEqualTo(Duration.ofMillis(500));
        assertThat(backoff.nextDelay()).isEqualTo(Duration.ofMillis(1000));
        assertThat(backoff.nextDelay()).isEqualTo(Duration.ofMillis(2000));
    }

    @Test
    @DisplayName("실제 난수로도 항상 [계산값의 절반, 계산값] 범위 안에 있고 상한을 넘지 않는다")
    void stayWithinBoundsWithRealRandom() {
        ReconnectBackoff backoff = new ReconnectBackoff(INITIAL, MAX, MIN, RandomGenerator.getDefault());

        for (int attempt = 0; attempt < 40; attempt++) {
            long capped = Math.min(1000L << Math.min(attempt, 20), MAX.toMillis());
            Duration delay = backoff.nextDelay();
            assertThat(delay.toMillis()).isBetween(capped / 2, capped);
        }
    }

    @Test
    @DisplayName("reset하면 다시 처음 대기 시간부터 시작한다")
    void resetStartsOver() {
        ReconnectBackoff backoff = new ReconnectBackoff(INITIAL, MAX, MIN, fixed(1.0));
        backoff.nextDelay();
        backoff.nextDelay();
        backoff.nextDelay();

        backoff.reset();

        assertThat(backoff.nextDelay()).isEqualTo(Duration.ofMillis(1000));
    }

    @Test
    @DisplayName("대기 시간은 연결 요청 한도(초당 5회)를 지키는 하한 아래로 내려가지 않는다")
    void respectsMinimumDelay() {
        ReconnectBackoff backoff = new ReconnectBackoff(Duration.ofMillis(100), MAX, Duration.ofMillis(200),
                fixed(0.0));

        assertThat(backoff.nextDelay()).isEqualTo(Duration.ofMillis(200));
    }

    @Test
    @DisplayName("시도 횟수가 아주 많아도 오버플로 없이 상한을 유지한다")
    void noOverflow() {
        ReconnectBackoff backoff = new ReconnectBackoff(INITIAL, MAX, MIN, fixed(1.0));

        Duration last = Duration.ZERO;
        for (int i = 0; i < 1000; i++) {
            last = backoff.nextDelay();
        }

        assertThat(last).isEqualTo(MAX);
    }
}
