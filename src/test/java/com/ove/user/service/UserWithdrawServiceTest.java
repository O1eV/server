package com.ove.user.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionSynchronizationUtils;

import com.ove.auth.repository.TokenRedisRepository;
import com.ove.global.exception.BusinessException;
import com.ove.global.exception.ErrorCode;
import com.ove.global.security.AuthUser;
import com.ove.global.security.JwtProperties;
import com.ove.user.domain.User;
import com.ove.user.domain.UserStatus;
import com.ove.user.repository.UserRepository;

@ExtendWith(MockitoExtension.class)
class UserWithdrawServiceTest {

    private static final String PASSWORD = "password123";
    private static final Duration ACCESS_TTL = Duration.ofMinutes(30);

    @Mock
    private UserRepository userRepository;
    @Mock
    private PasswordEncoder passwordEncoder;
    @Mock
    private TokenRedisRepository tokenRedisRepository;

    private UserWithdrawService service;

    @BeforeEach
    void setUp() {
        service = new UserWithdrawService(userRepository, passwordEncoder, tokenRedisRepository,
                new JwtProperties("test-secret-key-for-ove-backend-must-be-at-least-32-bytes",
                        ACCESS_TTL, Duration.ofDays(14)));
    }

    @AfterEach
    void clearSynchronization() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    @DisplayName("정상 탈퇴: 회원은 WITHDRAWN으로 바뀌고 개인정보가 익명화된다 (모의계좌는 지우지 않는다)")
    void withdrawsAndAnonymizes() {
        User user = user();
        AuthUser authUser = authUser(user.getId());
        given(userRepository.findByIdForUpdate(user.getId())).willReturn(Optional.of(user));
        given(passwordEncoder.matches(PASSWORD, "hashed")).willReturn(true);
        Instant before = Instant.now();

        service.withdraw(authUser, PASSWORD);

        assertThat(user.getStatus()).isEqualTo(UserStatus.WITHDRAWN);
        assertThat(user.isWithdrawn()).isTrue();
        assertThat(user.getDeletedAt()).isBetween(before, Instant.now());
        // 이메일·닉네임은 회원마다 다른 값으로 바뀌고(유일 제약 유지), 닉네임은 길이 제한(20자) 안이다
        assertThat(user.getEmail()).isEqualTo("withdrawn+" + user.getId() + "@deleted.invalid");
        assertThat(user.getNickname()).startsWith("탈퇴").hasSizeLessThanOrEqualTo(20);
        assertThat(user.getEmail()).doesNotContain("user@ove.com");
        assertThat(user.getNickname()).isNotEqualTo("오브");
        // 비밀번호 해시도 BCrypt 형식이 아닌 값으로 덮어써서 어떤 입력과도 일치하지 않는다
        assertThat(user.getPassword()).isEqualTo("!withdrawn");
    }

    @Test
    @DisplayName("서로 다른 회원은 서로 다른 익명 이메일·닉네임을 받는다 (유일 제약 충돌 없음)")
    void anonymizedValuesAreUniquePerUser() {
        User first = user();
        User second = user();
        first.withdraw(Instant.now());
        second.withdraw(Instant.now());

        assertThat(first.getEmail()).isNotEqualTo(second.getEmail());
        assertThat(first.getNickname()).isNotEqualTo(second.getNickname());
    }

    @Test
    @DisplayName("비밀번호가 틀리면 탈퇴 전용 메시지의 INVALID_CREDENTIALS이고 회원·Redis 어느 것도 바뀌지 않는다")
    void wrongPasswordChangesNothing() {
        User user = user();
        given(userRepository.findByIdForUpdate(user.getId())).willReturn(Optional.of(user));
        given(passwordEncoder.matches("wrong", "hashed")).willReturn(false);
        TransactionSynchronizationManager.initSynchronization();

        assertThatThrownBy(() -> service.withdraw(authUser(user.getId()), "wrong"))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> {
                            assertThat(e.getErrorCode()).isEqualTo(ErrorCode.INVALID_CREDENTIALS);
                            assertThat(e.getMessage()).isEqualTo("비밀번호가 일치하지 않습니다.");
                        });

        assertThat(user.getStatus()).isEqualTo(UserStatus.ACTIVE);
        assertThat(user.getEmail()).isEqualTo("user@ove.com");
        assertThat(user.getDeletedAt()).isNull();
                TransactionSynchronizationUtils.triggerAfterCommit();
        verifyNoInteractions(tokenRedisRepository);
    }

    @Test
    @DisplayName("이미 탈퇴한 회원(동시 요청의 뒤쪽 포함)과 없는 회원은 USER_NOT_FOUND이고 비밀번호도 확인하지 않는다")
    void alreadyWithdrawnOrMissingIsNotFound() {
        User withdrawn = user();
        withdrawn.withdraw(Instant.now());
        given(userRepository.findByIdForUpdate(withdrawn.getId())).willReturn(Optional.of(withdrawn));
        UUID missingId = UUID.randomUUID();
        given(userRepository.findByIdForUpdate(missingId)).willReturn(Optional.empty());

        for (UUID id : new UUID[] {withdrawn.getId(), missingId}) {
            assertThatThrownBy(() -> service.withdraw(authUser(id), PASSWORD))
                    .isInstanceOfSatisfying(BusinessException.class,
                            e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.USER_NOT_FOUND));
        }

        verifyNoInteractions(passwordEncoder, tokenRedisRepository);
    }

    @Test
    @DisplayName("Redis 정리는 DB 커밋 뒤에만 한다: 커밋 전에는 건드리지 않고, 커밋 뒤에 refresh 전부 삭제 + access 블랙리스트")
    void redisIsClearedOnlyAfterCommit() {
        User user = user();
        AuthUser authUser = authUser(user.getId());
        given(userRepository.findByIdForUpdate(user.getId())).willReturn(Optional.of(user));
        given(passwordEncoder.matches(PASSWORD, "hashed")).willReturn(true);
        TransactionSynchronizationManager.initSynchronization();

        service.withdraw(authUser, PASSWORD);

        verifyNoInteractions(tokenRedisRepository); // 커밋 전

        TransactionSynchronizationUtils.triggerAfterCommit();

        verify(tokenRedisRepository).deleteAllRefreshTokens(user.getId());
        ArgumentCaptor<Duration> ttl = ArgumentCaptor.forClass(Duration.class);
        verify(tokenRedisRepository).addToBlacklist(eq(authUser.accessTokenJti()), ttl.capture());
        assertThat(ttl.getValue()).isPositive().isLessThanOrEqualTo(ACCESS_TTL);
        // 다른 기기에 남은 access 토큰을 막는 탈퇴 표시: TTL은 access 최대 수명(설정값)
        verify(tokenRedisRepository).markWithdrawn(user.getId(), ACCESS_TTL);
    }

    @Test
    @DisplayName("트랜잭션이 롤백되면 Redis는 건드리지 않는다")
    void rollbackLeavesRedisUntouched() {
        User user = user();
        given(userRepository.findByIdForUpdate(user.getId())).willReturn(Optional.of(user));
        given(passwordEncoder.matches(PASSWORD, "hashed")).willReturn(true);
        TransactionSynchronizationManager.initSynchronization();

        service.withdraw(authUser(user.getId()), PASSWORD);
        // afterCommit 없이 롤백으로 끝나는 경우
        TransactionSynchronizationUtils.triggerAfterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);

        verifyNoInteractions(tokenRedisRepository);
    }

    @Test
    @DisplayName("커밋 뒤 Redis 정리가 실패해도 탈퇴는 성공으로 끝난다 (DB는 이미 커밋됨, 오류는 로그로 남김)")
    void redisFailureAfterCommitDoesNotFailWithdrawal() {
        User user = user();
        given(userRepository.findByIdForUpdate(user.getId())).willReturn(Optional.of(user));
        given(passwordEncoder.matches(PASSWORD, "hashed")).willReturn(true);
        doThrow(new IllegalStateException("redis down")).when(tokenRedisRepository).deleteAllRefreshTokens(any());
        TransactionSynchronizationManager.initSynchronization();

        assertThatCode(() -> {
            service.withdraw(authUser(user.getId()), PASSWORD);
            TransactionSynchronizationUtils.triggerAfterCommit();
        }).doesNotThrowAnyException();
        verify(tokenRedisRepository, never()).addToBlacklist(anyString(), any());
    }

    @Test
    @DisplayName("트랜잭션 동기화가 없는 상황이면 바로 정리한다")
    void runsImmediatelyWithoutTransactionSynchronization() {
        User user = user();
        given(userRepository.findByIdForUpdate(user.getId())).willReturn(Optional.of(user));
        given(passwordEncoder.matches(PASSWORD, "hashed")).willReturn(true);

        service.withdraw(authUser(user.getId()), PASSWORD);

        verify(tokenRedisRepository).deleteAllRefreshTokens(user.getId());
    }

    private User user() {
        User user = User.builder().email("user@ove.com").password("hashed").nickname("오브").build();
        ReflectionTestUtils.setField(user, "id", UUID.randomUUID());
        return user;
    }

    private AuthUser authUser(UUID userId) {
        return new AuthUser(userId, "access-jti", Instant.now().plus(Duration.ofMinutes(20)));
    }
}
