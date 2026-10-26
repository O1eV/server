package com.ove.signal.event;

import java.math.BigDecimal;
import java.time.Instant;

import com.ove.signal.domain.Signal;
import com.ove.signal.domain.SignalType;

/**
 * AI 신호가 바뀐 순간을 다른 도메인(푸시 알림 등)에 알린다.
 * 저장 트랜잭션이 커밋된 뒤에만 전달되므로, DB에 남지 않은 신호로 알림이 나가지 않는다.
 */
public record SignalChangedEvent(
        Long signalId,
        String symbol,
        SignalType previousSignal,
        SignalType signal,
        BigDecimal confidence,
        BigDecimal price,
        Instant generatedAt) {

    public static SignalChangedEvent from(Signal signal) {
        return new SignalChangedEvent(
                signal.getId(),
                signal.getSymbol(),
                signal.getPreviousSignal(),
                signal.getSignal(),
                signal.getConfidence(),
                signal.getPrice(),
                signal.getGeneratedAt());
    }
}
