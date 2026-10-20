package com.ove.paper.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.ove.paper.domain.PaperHolding;

public interface PaperHoldingRepository extends JpaRepository<PaperHolding, Long> {

    List<PaperHolding> findAllByPaperAccountUserIdOrderBySymbolAsc(UUID userId);

    Optional<PaperHolding> findByPaperAccountIdAndSymbol(Long paperAccountId, String symbol);

    @Modifying
    @Query("delete from PaperHolding paperHolding where paperHolding.paperAccount.user.id = :userId")
    void deleteAllByUserId(@Param("userId") UUID userId);
}
