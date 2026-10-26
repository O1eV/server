package com.ove.watchlist.domain;

import java.time.Instant;

import org.hibernate.annotations.CreationTimestamp;

import com.ove.user.domain.User;

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

@Entity
@Table(name = "watchlists", uniqueConstraints = @UniqueConstraint(columnNames = {"user_id", "symbol"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Watchlist {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(nullable = false, length = 20)
    private String symbol;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    // 사용자와 종목으로 관심 종목 한 줄을 만들 때 사용
    @Builder
    private Watchlist(User user, String symbol) {
        this.user = user;
        this.symbol = symbol;
    }
}
