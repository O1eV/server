package com.ove.signal.repository;

import java.time.Instant;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import com.ove.signal.domain.Signal;

public interface SignalRepository extends JpaRepository<Signal, Long> {

    Optional<Signal> findFirstBySymbolOrderByGeneratedAtDescIdDesc(String symbol);

    Page<Signal> findAllBySymbolAndPreviousSignalIsNotNullAndGeneratedAtBetween(
            String symbol, Instant from, Instant to, Pageable pageable);
}
