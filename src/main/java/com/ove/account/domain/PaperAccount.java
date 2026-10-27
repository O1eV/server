package com.ove.account.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;

import org.hibernate.annotations.CreationTimestamp;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import com.ove.user.domain.User;

/**
 * 모의투자 계좌. 실제 거래는 하지 않고 가상 잔고만 관리한다. 회원당 1개.
 */
@Entity
@Table(name = "paper_accounts")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PaperAccount {

    public static final String DEFAULT_CURRENCY = "KRW";
    private static final int BALANCE_SCALE = 8;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false, unique = true)
    private User user;

    // 소수 수량의 코인을 매수해도 잔고가 손실되지 않도록 8자리까지 저장한다.
    @Column(nullable = false, precision = 24, scale = BALANCE_SCALE)
    private BigDecimal balance;

    @Column(nullable = false, length = 3)
    private String currency;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Builder
    private PaperAccount(User user, BigDecimal balance, String currency) {
        this.user = user;
        this.balance = normalize(balance);
        this.currency = currency;
    }

    public void withdraw(BigDecimal amount) {
        this.balance = normalize(balance.subtract(amount));
    }

    public void deposit(BigDecimal amount) {
        this.balance = normalize(balance.add(amount));
    }

    public void reset(BigDecimal initialBalance) {
        this.balance = normalize(initialBalance);
    }

    private static BigDecimal normalize(BigDecimal value) {
        return value.setScale(BALANCE_SCALE, RoundingMode.HALF_UP);
    }
}
