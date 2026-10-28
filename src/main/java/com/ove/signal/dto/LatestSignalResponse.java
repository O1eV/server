package com.ove.signal.dto;

import java.math.BigDecimal;
import java.time.Instant;
import com.ove.signal.domain.Signal;

public record LatestSignalResponse(Long signalId, String symbol, String previousSignal, String signal,
        BigDecimal buyProbability, BigDecimal holdProbability, BigDecimal sellProbability,
        BigDecimal confidence, BigDecimal price, Instant generatedAt, boolean stale, String modelVersion) {
    public static LatestSignalResponse from(Signal value, Instant now) {
        return new LatestSignalResponse(value.getId(), value.getSymbol(), value.getPreviousSignal(), value.getSignal(),
            value.getBuyProbability(), value.getHoldProbability(), value.getSellProbability(), value.getConfidence(),
            value.getPrice(), value.getGeneratedAt(), !value.getGeneratedAt().plusSeconds(120).isAfter(now), value.getModelVersion());
    }
}
