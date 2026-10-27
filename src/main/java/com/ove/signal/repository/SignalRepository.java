package com.ove.signal.repository;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import com.ove.signal.domain.Signal;

public interface SignalRepository extends JpaRepository<Signal, Long> {
    Optional<Signal> findFirstBySymbolOrderByGeneratedAtDescIdDesc(String symbol);
}
