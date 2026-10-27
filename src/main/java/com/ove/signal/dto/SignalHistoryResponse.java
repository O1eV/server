package com.ove.signal.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import org.springframework.data.domain.Page;

import com.ove.signal.domain.Signal;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 신호 전환 이력 한 페이지. previousSignal이 있는 행(신호가 바뀐 시점)만 담는다.
 * 항목 필드는 LatestSignalResponse와 이름·형식을 맞췄고, 과거 행이라 stale은 없다.
 */
@Schema(description = "신호 전환 이력 페이지(최신순)")
public record SignalHistoryResponse(List<Item> items, int page, int size, long totalElements, int totalPages) {

    public static SignalHistoryResponse from(Page<Signal> signals) {
        return new SignalHistoryResponse(signals.getContent().stream().map(Item::from).toList(),
                signals.getNumber(), signals.getSize(), signals.getTotalElements(), signals.getTotalPages());
    }

    public record Item(Long signalId, String symbol, String previousSignal, String signal,
            BigDecimal buyProbability, BigDecimal holdProbability, BigDecimal sellProbability,
            BigDecimal confidence, BigDecimal price, Instant generatedAt, String modelVersion) {

        static Item from(Signal value) {
            return new Item(value.getId(), value.getSymbol(), value.getPreviousSignal(), value.getSignal(),
                    value.getBuyProbability(), value.getHoldProbability(), value.getSellProbability(),
                    value.getConfidence(), value.getPrice(), value.getGeneratedAt(), value.getModelVersion());
        }
    }
}
