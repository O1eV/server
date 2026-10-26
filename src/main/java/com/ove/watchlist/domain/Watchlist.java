package com.ove.watchlist.domain;

import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.CreationTimestamp;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "watchlists", uniqueConstraints = @UniqueConstraint(columnNames = {"user_id", "symbol"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Watchlist {
    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;
    @Column(name = "user_id", nullable = false)
    private UUID userId;
    @Column(nullable = false, length = 30)
    private String symbol;
    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private Instant addedAt;

    public Watchlist(UUID userId, String symbol) {
        this.userId = userId;
        this.symbol = symbol;
    }
}
