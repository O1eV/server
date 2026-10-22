package com.ove.support;

import java.time.Duration;
import java.util.function.BooleanSupplier;

/**
 * 비동기로 일어나는 일을 조건이 맞을 때까지 기다린다(별도 라이브러리 없이 쓰는 간단한 폴링).
 */
public final class Await {

    private Await() {
    }

    public static boolean until(BooleanSupplier condition, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            try {
                if (condition.getAsBoolean()) {
                    return true;
                }
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            } catch (RuntimeException e) {
                // 아직 준비되지 않은 상태에서 조건을 평가하다 난 오류는 다음 폴링에서 다시 본다.
            }
        }
        return condition.getAsBoolean();
    }

    public static boolean until(BooleanSupplier condition) {
        return until(condition, Duration.ofSeconds(5));
    }
}
