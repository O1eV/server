package com.ove.account.repository;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.ove.account.domain.PaperAccount;

public interface PaperAccountRepository extends JpaRepository<PaperAccount, Long> {

    Optional<PaperAccount> findByUserId(UUID userId);
}
