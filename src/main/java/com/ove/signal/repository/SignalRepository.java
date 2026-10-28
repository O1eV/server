package com.ove.signal.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.ove.signal.domain.Signal;

public interface SignalRepository extends JpaRepository<Signal, Long> {

    Optional<Signal> findFirstBySymbolOrderByGeneratedAtDescIdDesc(String symbol);

    /**
     * 여러 종목의 최신 신호를 한 번에 조회한다.
     * generatedAt이 같으면 id가 큰 쪽을 최신으로 본다(단건 조회의 정렬 기준과 같다).
     */
    @Query("""
            select s from Signal s
            where s.symbol in :symbols
              and not exists (
                  select 1 from Signal newer
                  where newer.symbol = s.symbol
                    and (newer.generatedAt > s.generatedAt
                         or (newer.generatedAt = s.generatedAt and newer.id > s.id))
              )
            """)
    List<Signal> findLatestBySymbolIn(@Param("symbols") Collection<String> symbols);
}
