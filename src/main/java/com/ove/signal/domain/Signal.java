package com.ove.signal.domain;

import java.math.BigDecimal;
import java.time.Instant;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.AccessLevel;

@Entity
@Table(name = "signals", indexes = @Index(name = "idx_signals_symbol_generated", columnList = "symbol,generated_at"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Signal {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false) private String symbol;
    private String previousSignal;
    @Column(nullable = false) private String signal;
    @Column(nullable = false, precision = 10, scale = 8) private BigDecimal buyProbability;
    @Column(nullable = false, precision = 10, scale = 8) private BigDecimal holdProbability;
    @Column(nullable = false, precision = 10, scale = 8) private BigDecimal sellProbability;
    @Column(nullable = false, precision = 10, scale = 8) private BigDecimal confidence;
    @Column(precision = 24, scale = 8) private BigDecimal price;
    @Column(nullable = false) private Instant generatedAt;
    private String modelVersion;

    public Signal(String symbol, String previousSignal, String signal, BigDecimal buyProbability,
            BigDecimal holdProbability, BigDecimal sellProbability, BigDecimal confidence,
            BigDecimal price, Instant generatedAt, String modelVersion) {
        this.symbol = symbol; this.previousSignal = previousSignal; this.signal = signal;
        this.buyProbability = buyProbability; this.holdProbability = holdProbability;
        this.sellProbability = sellProbability; this.confidence = confidence;
        this.price = price; this.generatedAt = generatedAt; this.modelVersion = modelVersion;
    }
}
