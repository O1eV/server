package com.ove.account.domain;

import java.math.BigDecimal;
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

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false, unique = true)
    private User user;

    // 코인 체결가가 소수점까지 내려올 수 있어 소수 둘째 자리까지 저장한다.
    @Column(nullable = false, precision = 20, scale = 2)
    private BigDecimal balance;

    @Column(nullable = false, length = 3)
    private String currency;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Builder
    private PaperAccount(User user, BigDecimal balance, String currency) {
        this.user = user;
        this.balance = balance;
        this.currency = currency;
    }
}
