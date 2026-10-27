package com.ove.paper.domain;

import java.math.BigDecimal;
import java.time.Instant;

import org.hibernate.annotations.CreationTimestamp;

import com.ove.account.domain.PaperAccount;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 즉시 체결된 모의 주문 이력. 주문 당시의 체결가·금액을 보존해 나중에 시세가 변해도 이력이 바뀌지 않는다.
 */
@Entity
@Table(name = "paper_orders")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PaperOrder {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "paper_account_id", nullable = false)
    private PaperAccount paperAccount;

    @Column(nullable = false, length = 30)
    private String symbol;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 4)
    private OrderSide side;

    @Column(nullable = false, precision = 24, scale = 12)
    private BigDecimal quantity;

    @Column(nullable = false, precision = 24, scale = 8)
    private BigDecimal price;

    @Column(nullable = false, precision = 30, scale = 8)
    private BigDecimal amount;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private Instant orderedAt;

    @Builder
    private PaperOrder(PaperAccount paperAccount, String symbol, OrderSide side, BigDecimal quantity,
                       BigDecimal price, BigDecimal amount) {
        this.paperAccount = paperAccount;
        this.symbol = symbol;
        this.side = side;
        this.quantity = quantity;
        this.price = price;
        this.amount = amount;
    }
}
