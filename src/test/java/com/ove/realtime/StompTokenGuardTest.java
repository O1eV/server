package com.ove.realtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.ove.auth.repository.TokenRedisRepository;
import com.ove.realtime.config.StompPrincipal;
import com.ove.realtime.config.StompTokenGuard;

class StompTokenGuardTest {

    private final TokenRedisRepository tokenRedisRepository = mock(TokenRedisRepository.class);
    private final StompTokenGuard guard = new StompTokenGuard(tokenRedisRepository);
    private final UUID userId = UUID.randomUUID();

    @Test
    @DisplayName("유효한 토큰이면 null이고, Redis는 isRevoked(jti, userId) 한 번만 조회한다")
    void validToken() {
        StompPrincipal principal = principal(Duration.ofMinutes(10));

        assertThat(guard.check(principal)).isNull();

        verify(tokenRedisRepository).isRevoked("jti-1", userId);
        verifyNoMoreInteractions(tokenRedisRepository);
    }

    @Test
    @DisplayName("로그아웃(블랙리스트)이나 탈퇴 표시가 있어 isRevoked가 true이면 UNAUTHORIZED")
    void revokedToken() {
        given(tokenRedisRepository.isRevoked("jti-1", userId)).willReturn(true);

        assertThat(guard.check(principal(Duration.ofMinutes(10)))).isEqualTo("UNAUTHORIZED");
    }

    @Test
    @DisplayName("만료된 토큰은 Redis를 보지 않고 TOKEN_EXPIRED")
    void expiredToken() {
        assertThat(guard.check(principal(Duration.ofSeconds(-1)))).isEqualTo("TOKEN_EXPIRED");

        verifyNoInteractions(tokenRedisRepository);
    }

    @Test
    @DisplayName("회원 ID가 UUID 형식이 아니면 예외 없이 UNAUTHORIZED")
    void malformedUserId() {
        StompPrincipal principal = new StompPrincipal("not-a-uuid", "jti-1", Instant.now().plus(Duration.ofMinutes(10)));

        assertThat(guard.check(principal)).isEqualTo("UNAUTHORIZED");

        verifyNoInteractions(tokenRedisRepository);
    }

    private StompPrincipal principal(Duration validFor) {
        return new StompPrincipal(userId.toString(), "jti-1", Instant.now().plus(validFor));
    }
}
