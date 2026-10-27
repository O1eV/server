package com.ove.signal.repository;

import java.time.Instant;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import com.ove.signal.domain.Signal;

public interface SignalRepository extends JpaRepository<Signal, Long> {
    Optional<Signal> findFirstBySymbolOrderByGeneratedAtDescIdDesc(String symbol);

    // 신호가 바뀐 행(previousSignal 있음)만 기간(양 끝 포함)으로 조회한다. 정렬은 Pageable로 넘긴다.
    Page<Signal> findBySymbolAndPreviousSignalIsNotNullAndGeneratedAtBetween(
            String symbol, Instant from, Instant to, Pageable pageable);
}
