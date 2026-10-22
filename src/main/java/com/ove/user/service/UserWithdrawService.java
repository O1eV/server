package com.ove.user.service;

import java.time.Duration;
import java.time.Instant;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.ove.account.service.PaperAccountService;
import com.ove.auth.repository.TokenRedisRepository;
import com.ove.global.exception.BusinessException;
import com.ove.global.exception.ErrorCode;
import com.ove.global.security.AuthUser;
import com.ove.user.domain.User;
import com.ove.user.repository.UserRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 회원탈퇴. 비밀번호를 확인한 뒤 회원을 soft delete(개인정보 익명화)하고 모의계좌를 지운다.
 * 로그인 정보(Redis)는 DB 트랜잭션이 커밋된 뒤에 정리한다. DB가 롤백됐는데 토큰만 지워지면 안 되기 때문이다.
 *
 * 약관 동의 이력(terms_agreements)은 법적 보관 성격이라 지우지 않고 user_id로 남긴다.
 * 개인정보는 users 행에서 익명화되므로 이력만으로는 누구인지 알 수 없다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserWithdrawService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final PaperAccountService paperAccountService;
    private final TokenRedisRepository tokenRedisRepository;

    @Transactional
    public void withdraw(AuthUser authUser, String password) {
        // 없는 회원과 이미 탈퇴한 회원은 구분하지 않고 404로 응답한다
        User user = userRepository.findById(authUser.userId())
                .filter(found -> !found.isWithdrawn())
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));

        if (!passwordEncoder.matches(password, user.getPassword())) {
            throw new BusinessException(ErrorCode.INVALID_CREDENTIALS);
        }

        user.withdraw(Instant.now());
        paperAccountService.deleteAccountOf(user.getId());
        // TODO(새 기능이 사용자 데이터를 가지게 되면 여기에 삭제 호출을 추가한다): 관심종목, 사용자 API 키, 기기 토큰,
        //  모의투자 주문·보유(PR #19: PaperOrder → PaperHolding 순으로 지운 뒤 모의계좌를 지워야 외래키 오류가 없다)

        afterCommit(() -> clearLoginState(authUser));
    }

    // 커밋 뒤 정리: 모든 기기의 refresh 삭제 + 지금 쓰던 access 토큰 블랙리스트(남은 만료 시간만큼)
    private void clearLoginState(AuthUser authUser) {
        try {
            int removed = tokenRedisRepository.deleteAllRefreshTokens(authUser.userId());
            Duration remaining = Duration.between(Instant.now(), authUser.accessTokenExpiresAt());
            tokenRedisRepository.addToBlacklist(authUser.accessTokenJti(), remaining);
            log.info("회원탈퇴 후 로그인 정보 정리: userId={}, 삭제한 refresh={}개", authUser.userId(), removed);
        } catch (RuntimeException e) {
            // DB는 이미 커밋돼서 되돌릴 수 없다. 남은 refresh는 재발급 때 회원 상태를 확인해서 막으므로 로그만 남긴다.
            log.error("회원탈퇴 후 Redis 정리 실패: userId={}", authUser.userId(), e);
        }
    }

    private void afterCommit(Runnable action) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            action.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                action.run();
            }
        });
    }
}
