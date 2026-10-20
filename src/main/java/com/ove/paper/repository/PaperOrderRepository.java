package com.ove.paper.repository;

import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.ove.paper.domain.PaperOrder;

public interface PaperOrderRepository extends JpaRepository<PaperOrder, Long> {

    Page<PaperOrder> findAllByPaperAccountUserId(UUID userId, Pageable pageable);

    Page<PaperOrder> findAllByPaperAccountUserIdAndSymbol(UUID userId, String symbol, Pageable pageable);

    @Modifying
    @Query("delete from PaperOrder paperOrder where paperOrder.paperAccount.user.id = :userId")
    void deleteAllByUserId(@Param("userId") UUID userId);
}
