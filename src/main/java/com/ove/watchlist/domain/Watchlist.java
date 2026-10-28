package com.ove.watchlist.domain;

import java.time.Instant;
import java.util.UUID;

import org.hibernate.annotations.CreationTimestamp;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 회원이 관심 종목으로 등록한 종목. (회원, 종목) 쌍은 유일하다.
 * 회원 삭제 시 함께 지울 수 있도록 User 연관관계 없이 user_id만 들고 있다.
 */
@Entity
@Table(name = "watchlists", uniqueConstraints = @UniqueConstraint(
        name = "uk_watchlists_user_symbol", columnNames = {"user_id", "symbol"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Watchlist {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    // 대문자로 정규화한 종목 코드 (예: KRW-BTC)
    @Column(nullable = false, length = 20)
    private String symbol;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private Instant addedAt;

    @Builder
    private Watchlist(UUID userId, String symbol) {
        this.userId = userId;
        this.symbol = symbol;
    }
}
