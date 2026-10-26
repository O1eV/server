package com.ove.signal.dto;

import java.math.BigDecimal;
import java.time.Instant;

import com.ove.signal.domain.Signal;

import lombok.Builder;
import lombok.Getter;

/**
 * 최신 AI 신호. stale은 모델 호출이 실패해 마지막 신호를 그대로 보여 주는 상태다.
 */
@Getter
@Builder
public class SignalResponse {

    private final Long signalId;
    private final String symbol;
    private final String previousSignal;
    private final String signal;
    private final BigDecimal buyProbability;
    private final BigDecimal holdProbability;
    private final BigDecimal sellProbability;
    private final BigDecimal confidence;
    private final BigDecimal price;
    private final Instant generatedAt;
    private final boolean stale;

    public static SignalResponse from(Signal signal, boolean stale) {
        return SignalResponse.builder()
                .signalId(signal.getId())
                .symbol(signal.getSymbol())
                .previousSignal(signal.getPreviousSignal() == null ? null : signal.getPreviousSignal().name())
                .signal(signal.getSignal().name())
                .buyProbability(signal.getBuyProbability())
                .holdProbability(signal.getHoldProbability())
                .sellProbability(signal.getSellProbability())
                .confidence(signal.getConfidence())
                .price(signal.getPrice())
                .generatedAt(signal.getGeneratedAt())
                .stale(stale)
                .build();
    }
}
