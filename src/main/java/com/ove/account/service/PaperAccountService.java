package com.ove.account.service;

import java.math.BigDecimal;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ove.account.domain.PaperAccount;
import com.ove.account.repository.PaperAccountRepository;
import com.ove.user.domain.User;

/**
 * 모의계좌 관리. 지금은 회원가입 시 초기 계좌를 만드는 기능만 있다.
 */
@Service
public class PaperAccountService {

    private final PaperAccountRepository paperAccountRepository;
    private final BigDecimal initialBalance;

    public PaperAccountService(PaperAccountRepository paperAccountRepository,
                               @Value("${paper-account.initial-balance:10000000}") BigDecimal initialBalance) {
        this.paperAccountRepository = paperAccountRepository;
        this.initialBalance = initialBalance;
    }

    // 회원가입 트랜잭션 안에서 호출되어 가입과 함께 커밋·롤백된다.
    @Transactional
    public PaperAccount createInitialAccount(User user) {
        return paperAccountRepository.save(PaperAccount.builder()
                .user(user)
                .balance(initialBalance)
                .currency(PaperAccount.DEFAULT_CURRENCY)
                .build());
    }

    // 회원탈퇴 트랜잭션 안에서 호출되어 탈퇴와 함께 커밋·롤백된다. 계좌가 없으면 아무것도 하지 않는다.
    @Transactional
    public void deleteAccountOf(UUID userId) {
        paperAccountRepository.findByUserId(userId).ifPresent(paperAccountRepository::delete);
    }
}
