package com.ove.market.collector;

import java.time.Duration;
import java.util.random.RandomGenerator;

/**
 * 재연결 대기 시간 계산. 1s → 2s → 4s … 로 늘다가 상한(max)에서 멈춘다.
 * 여러 인스턴스가 한꺼번에 재접속하지 않도록 대기 시간의 절반~전부 사이에서 무작위로 고른다(equal jitter).
 * minDelay는 업비트의 연결 요청 한도(초당 5회)를 넘지 않게 하는 하한이다.
 */
public class ReconnectBackoff {

    private static final int MAX_EXPONENT = 20;

    private final Duration initial;
    private final Duration max;
    private final Duration minDelay;
    private final RandomGenerator random;
    private int attempt;

    public ReconnectBackoff(Duration initial, Duration max, Duration minDelay, RandomGenerator random) {
        this.initial = initial;
        this.max = max;
        this.minDelay = minDelay;
        this.random = random;
    }

    public synchronized Duration nextDelay() {
        long exponentialMillis = initial.toMillis() << Math.min(attempt, MAX_EXPONENT);
        long cappedMillis = Math.min(exponentialMillis, max.toMillis());
        attempt++;

        long half = cappedMillis / 2;
        long jittered = half + (long) (random.nextDouble() * (cappedMillis - half));
        return Duration.ofMillis(Math.max(jittered, minDelay.toMillis()));
    }

    // 연결이 정상으로 확인되면(첫 메시지 수신) 다음 끊김은 다시 짧은 대기부터 시작한다.
    public synchronized void reset() {
        attempt = 0;
    }
}
