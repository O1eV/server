package com.ove.user.service;

import java.time.Duration;
import java.time.Instant;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.ove.auth.repository.TokenRedisRepository;
import com.ove.global.exception.BusinessException;
import com.ove.global.exception.ErrorCode;
import com.ove.global.security.AuthUser;
import com.ove.global.security.JwtProperties;
import com.ove.user.domain.User;
import com.ove.user.repository.UserRepository;
import com.ove.watchlist.repository.WatchlistRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 회원탈퇴. 비밀번호를 확인한 뒤 회원을 soft delete(개인정보 익명화)하고 관심종목을 지운다.
 * 로그인 정보(Redis)는 DB 트랜잭션이 커밋된 뒤에 정리한다. DB가 롤백됐는데 토큰만 지워지면 안 되기 때문이다.
 *
 * 모의계좌는 지우지 않고 남긴다. 계좌에는 개인정보가 없고(잔액·통화뿐), 모의투자 주문·보유가 계좌를 외래키로 참조해서
 * 계좌를 지우면 거래 이력이 있는 회원의 탈퇴가 머지 순서나 이력에 따라 실패할 수 있기 때문이다. 회원 행이 익명화되어 남으므로 계좌도 외래키가 유지된다.
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
    private final TokenRedisRepository tokenRedisRepository;
    private final JwtProperties jwtProperties;
    private final WatchlistRepository watchlistRepository;

    @Transactional
    public void withdraw(AuthUser authUser, String password) {
        // 같은 회원의 동시 탈퇴 요청을 한 줄로 세우려고 회원 행에 쓰기 잠금을 건다.
        // 뒤에 온 요청은 앞 요청이 끝난 뒤 탈퇴된 상태를 읽어 404로 끝난다(잠금이 없으면 둘 다 ACTIVE로 읽고 충돌해 500이 난다).
        // 없는 회원과 이미 탈퇴한 회원은 구분하지 않고 404로 응답한다
        User user = userRepository.findByIdForUpdate(authUser.userId())
                .filter(found -> !found.isWithdrawn())
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));

        if (!passwordEncoder.matches(password, user.getPassword())) {
            // 코드·상태(401 INVALID_CREDENTIALS)는 명세 그대로 두고 탈퇴 화면에 맞는 메시지만 쓴다.
            throw new BusinessException(ErrorCode.INVALID_CREDENTIALS, "비밀번호가 일치하지 않습니다.");
        }

        user.withdraw(Instant.now());
        // 관심종목은 회원이 고른 정보라 같은 트랜잭션에서 지운다(탈퇴가 롤백되면 함께 되돌아간다).
        watchlistRepository.deleteAllByUserId(user.getId());
        // TODO(새 기능이 사용자 데이터를 가지게 되면 여기에 삭제 호출을 추가한다): 사용자 API 키, 기기 토큰.
        //  모의계좌·주문·보유는 개인정보가 없어 지우지 않고 익명화된 회원에 연결된 채로 남긴다.

        afterCommit(() -> clearLoginState(authUser));
    }

    // 커밋 뒤 정리: 모든 기기의 refresh 삭제 + 지금 쓰던 access 토큰 블랙리스트(남은 만료 시간만큼)
    // + 탈퇴 표시(withdrawn:{userId}, TTL = access 최대 수명). 다른 기기에 남은 access는 블랙리스트에 없지만
    // 이 표시 때문에 필터에서 401로 막혀, 만료까지 최대 30분 동안 다른 API를 쓰는 일이 없다.
    private void clearLoginState(AuthUser authUser) {
        try {
            int removed = tokenRedisRepository.deleteAllRefreshTokens(authUser.userId());
            tokenRedisRepository.markWithdrawn(authUser.userId(), jwtProperties.accessTokenExpiration());
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
