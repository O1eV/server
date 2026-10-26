package com.ove.watchlist.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.ove.watchlist.domain.Watchlist;

public interface WatchlistRepository extends JpaRepository<Watchlist, Long> {

    boolean existsByUserIdAndSymbol(UUID userId, String symbol);

    long countByUserId(UUID userId);

    Optional<Watchlist> findByUserIdAndSymbol(UUID userId, String symbol);

    // 등록 순서(오래된 것부터). 같은 시각이면 id 순.
    List<Watchlist> findByUserIdOrderByAddedAtAscIdAsc(UUID userId);

    // 회원탈퇴 때 한 번에 지우는 용도. 벌크 쿼리라 영속성 컨텍스트를 거치지 않는다.
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("delete from Watchlist w where w.userId = :userId")
    int deleteAllByUserId(@Param("userId") UUID userId);
}
