package com.ove.global.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;

class JwtProviderTest {

    private static final String SECRET = "test-secret-key-for-ove-backend-must-be-at-least-32-bytes";

    private final JwtProvider jwtProvider = new JwtProvider(
            new JwtProperties(SECRET, Duration.ofMinutes(30), Duration.ofDays(14)));

    @Test
    void access_토큰은_sub_jti_type을_담는다() {
        UUID userId = UUID.randomUUID();

        IssuedToken issued = jwtProvider.createAccessToken(userId);
        Claims claims = jwtProvider.parse(issued.token());

        assertThat(claims.getSubject()).isEqualTo(userId.toString());
        assertThat(claims.getId()).isEqualTo(issued.jti());
        assertThat(jwtProvider.getTokenType(claims)).isEqualTo(TokenType.ACCESS);
        assertThat(claims.getExpiration().toInstant().getEpochSecond())
                .isEqualTo(issued.expiresAt().getEpochSecond());
    }

    @Test
    void refresh_토큰은_REFRESH_타입이고_jti가_매번_다르다() {
        UUID userId = UUID.randomUUID();

        IssuedToken first = jwtProvider.createRefreshToken(userId);
        IssuedToken second = jwtProvider.createRefreshToken(userId);

        assertThat(jwtProvider.getTokenType(jwtProvider.parse(first.token()))).isEqualTo(TokenType.REFRESH);
        assertThat(first.jti()).isNotEqualTo(second.jti());
    }

    @Test
    void 만료된_토큰은_ExpiredJwtException() {
        JwtProvider expiredProvider = new JwtProvider(
                new JwtProperties(SECRET, Duration.ofSeconds(-10), Duration.ofDays(14)));
        String token = expiredProvider.createAccessToken(UUID.randomUUID()).token();

        assertThatThrownBy(() -> jwtProvider.parse(token)).isInstanceOf(ExpiredJwtException.class);
    }

    @Test
    void 다른_키로_서명된_토큰은_JwtException() {
        JwtProvider otherProvider = new JwtProvider(new JwtProperties(
                "another-secret-key-for-ove-backend-at-least-32-bytes", Duration.ofMinutes(30), Duration.ofDays(14)));
        String token = otherProvider.createAccessToken(UUID.randomUUID()).token();

        assertThatThrownBy(() -> jwtProvider.parse(token)).isInstanceOf(JwtException.class);
    }

    @Test
    void 형식이_깨진_토큰은_JwtException() {
        assertThatThrownBy(() -> jwtProvider.parse("not-a-jwt")).isInstanceOf(JwtException.class);
    }
}
