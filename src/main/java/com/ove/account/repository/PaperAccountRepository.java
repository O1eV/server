package com.ove.account.repository;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

import com.ove.account.domain.PaperAccount;

public interface PaperAccountRepository extends JpaRepository<PaperAccount, Long> {

    @EntityGraph(attributePaths = "user")
    Optional<PaperAccount> findByUserId(UUID userId);

    /**
     * 주문·초기화가 동시에 들어와도 현금 잔고가 음수가 되지 않도록 계좌 행을 잠근다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select paperAccount from PaperAccount paperAccount where paperAccount.user.id = :userId")
    Optional<PaperAccount> findWithLockByUserId(@Param("userId") UUID userId);
}
