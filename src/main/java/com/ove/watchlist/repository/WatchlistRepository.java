package com.ove.watchlist.repository;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import com.ove.watchlist.domain.Watchlist;

public interface WatchlistRepository extends JpaRepository<Watchlist, UUID> {
    List<Watchlist> findByUserIdOrderByAddedAtAscSymbolAsc(UUID userId);
    long countByUserId(UUID userId);
    boolean existsByUserIdAndSymbol(UUID userId, String symbol);
    long deleteByUserIdAndSymbol(UUID userId, String symbol);
}
