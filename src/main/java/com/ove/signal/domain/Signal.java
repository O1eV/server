package com.ove.signal.domain;

import java.math.BigDecimal;
import java.time.Instant;

import org.hibernate.annotations.CreationTimestamp;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** AI가 생성한 신호 스냅샷. 모든 산출 결과를 저장하고 신호가 바뀐 행만 이력 API에서 보여 준다. */
@Entity
@Table(name = "signals", indexes = {
        @Index(name = "idx_signals_symbol_generated_at", columnList = "symbol, generated_at")
})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Signal {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 30)
    private String symbol;

    @Enumerated(EnumType.STRING)
    @Column(length = 4)
    private SignalType previousSignal;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 4)
    private SignalType signal;

    @Column(nullable = false, precision = 7, scale = 6)
    private BigDecimal buyProbability;

    @Column(nullable = false, precision = 7, scale = 6)
    private BigDecimal holdProbability;

    @Column(nullable = false, precision = 7, scale = 6)
    private BigDecimal sellProbability;

    @Column(nullable = false, precision = 7, scale = 6)
    private BigDecimal confidence;

    @Column(nullable = false, precision = 24, scale = 8)
    private BigDecimal price;

    @CreationTimestamp
    @Column(name = "generated_at", nullable = false, updatable = false)
    private Instant generatedAt;

    @Builder
    private Signal(String symbol, SignalType previousSignal, SignalType signal, BigDecimal buyProbability,
                   BigDecimal holdProbability, BigDecimal sellProbability, BigDecimal confidence,
                   BigDecimal price, Instant generatedAt) {
        this.symbol = symbol;
        this.previousSignal = previousSignal;
        this.signal = signal;
        this.buyProbability = buyProbability;
        this.holdProbability = holdProbability;
        this.sellProbability = sellProbability;
        this.confidence = confidence;
        this.price = price;
        this.generatedAt = generatedAt;
    }
}
