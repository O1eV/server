package com.ove.watchlist.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import com.ove.watchlist.domain.Watchlist;

public interface WatchlistRepository extends JpaRepository<Watchlist, Long> {

    List<Watchlist> findAllByUserIdOrderByIdAsc(UUID userId);

    boolean existsByUserIdAndSymbol(UUID userId, String symbol);

    long countByUserId(UUID userId);

    // 내 관심 종목 하나를 쿼리 한 번으로 지울 때 사용 (지운 개수를 돌려줘서, 동시에 두 번 지워도 두 번째는 0)
    @Modifying
    @Query("delete from Watchlist w where w.user.id = :userId and w.symbol = :symbol")
    int deleteByUserIdAndSymbol(UUID userId, String symbol);
}
