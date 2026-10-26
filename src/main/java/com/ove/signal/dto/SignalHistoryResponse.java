package com.ove.signal.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import org.springframework.data.domain.Page;

import com.ove.signal.domain.Signal;

import lombok.Builder;
import lombok.Getter;

/**
 * 신호 전환 이력. 같은 신호가 이어지는 스냅샷은 previousSignal이 null이라 여기에 포함되지 않는다.
 */
@Getter
@Builder
public class SignalHistoryResponse {

    private final List<Item> items;
    private final int page;
    private final int size;
    private final long totalElements;
    private final int totalPages;

    public static SignalHistoryResponse from(Page<Signal> signals) {
        return SignalHistoryResponse.builder()
                .items(signals.getContent().stream().map(Item::from).toList())
                .page(signals.getNumber())
                .size(signals.getSize())
                .totalElements(signals.getTotalElements())
                .totalPages(signals.getTotalPages())
                .build();
    }

    @Getter
    @Builder
    public static class Item {

        private final Long signalId;
        private final String previousSignal;
        private final String signal;
        private final BigDecimal buyProbability;
        private final BigDecimal holdProbability;
        private final BigDecimal sellProbability;
        private final BigDecimal confidence;
        private final BigDecimal price;
        private final Instant generatedAt;

        private static Item from(Signal signal) {
            return Item.builder()
                    .signalId(signal.getId())
                    .previousSignal(signal.getPreviousSignal() == null ? null : signal.getPreviousSignal().name())
                    .signal(signal.getSignal().name())
                    .buyProbability(signal.getBuyProbability())
                    .holdProbability(signal.getHoldProbability())
                    .sellProbability(signal.getSellProbability())
                    .confidence(signal.getConfidence())
                    .price(signal.getPrice())
                    .generatedAt(signal.getGeneratedAt())
                    .build();
        }
    }
}
