package com.ove.paper.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import com.ove.account.domain.PaperAccount;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 모의투자 계좌가 보유한 종목 수량과 이동평균 매수가.
 */
@Entity
@Table(name = "paper_holdings", uniqueConstraints = {
        @UniqueConstraint(name = "uk_paper_holdings_account_symbol", columnNames = {"paper_account_id", "symbol"})
})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PaperHolding {

    private static final int QUANTITY_SCALE = 12;
    private static final int PRICE_SCALE = 8;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "paper_account_id", nullable = false)
    private PaperAccount paperAccount;

    @Column(nullable = false, length = 30)
    private String symbol;

    @Column(nullable = false, precision = 24, scale = QUANTITY_SCALE)
    private BigDecimal quantity;

    @Column(nullable = false, precision = 24, scale = PRICE_SCALE)
    private BigDecimal averagePurchasePrice;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private java.time.Instant createdAt;

    @UpdateTimestamp
    @Column(nullable = false)
    private java.time.Instant updatedAt;

    @Builder
    private PaperHolding(PaperAccount paperAccount, String symbol, BigDecimal quantity,
                         BigDecimal averagePurchasePrice) {
        this.paperAccount = paperAccount;
        this.symbol = symbol;
        this.quantity = quantity.setScale(QUANTITY_SCALE, RoundingMode.HALF_UP);
        this.averagePurchasePrice = averagePurchasePrice.setScale(PRICE_SCALE, RoundingMode.HALF_UP);
    }

    /**
     * 추가 매수한 수량을 반영해 이동평균 매수가를 다시 계산한다.
     */
    public void buy(BigDecimal buyQuantity, BigDecimal price) {
        BigDecimal previousAmount = quantity.multiply(averagePurchasePrice);
        BigDecimal buyAmount = buyQuantity.multiply(price);
        BigDecimal newQuantity = quantity.add(buyQuantity);

        this.quantity = newQuantity.setScale(QUANTITY_SCALE, RoundingMode.HALF_UP);
        this.averagePurchasePrice = previousAmount.add(buyAmount)
                .divide(newQuantity, PRICE_SCALE, RoundingMode.HALF_UP);
    }

    public void sell(BigDecimal sellQuantity) {
        this.quantity = quantity.subtract(sellQuantity).setScale(QUANTITY_SCALE, RoundingMode.HALF_UP);
    }
}
