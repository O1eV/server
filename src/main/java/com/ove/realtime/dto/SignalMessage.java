package com.ove.realtime.dto;

import java.math.BigDecimal;
import java.time.Instant;

import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
public class SignalMessage {

    private final String type = "SIGNAL";
    private String previousSignal;
    private java.util.Map<String, BigDecimal> probabilities;
    private Instant generatedAt;
    private String symbol;
    private String signal;
    private BigDecimal confidence;
    private boolean stale;
    private Instant time;

    public SignalMessage(String symbol, String previousSignal, String signal,
                         java.util.Map<String, BigDecimal> probabilities, BigDecimal confidence, boolean stale, Instant time) {
        this(symbol, signal, confidence, stale, time);
        this.previousSignal = previousSignal;
        this.probabilities = probabilities;
    }

    // /topic/signals/{symbol}로 보낼 신호 메시지를 만들 때 사용 (stale은 모델이 응답 못 해 예전 신호라는 뜻)
    public SignalMessage(String symbol, String signal, BigDecimal confidence, boolean stale, Instant time) {
        this.symbol = symbol;
        this.signal = signal;
        this.confidence = confidence;
        this.stale = stale;
        this.time = time;
        this.generatedAt = time;
    }
}
