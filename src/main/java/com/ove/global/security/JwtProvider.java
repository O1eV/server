package com.ove.global.security;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

import javax.crypto.SecretKey;

import org.springframework.stereotype.Component;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtBuilder;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

/**
 * HS256 JWT 발급·검증. claim은 sub=userId, jti, type(ACCESS/REFRESH)을 쓰고,
 * refresh 토큰에는 로그인 상태 유지 여부(rememberMe)를 함께 넣는다.
 */
@Component
public class JwtProvider {

    private static final String TOKEN_TYPE_CLAIM = "type";
    private static final String REMEMBER_ME_CLAIM = "rememberMe";

    private final JwtProperties jwtProperties;
    private final SecretKey secretKey;

    public JwtProvider(JwtProperties jwtProperties) {
        this.jwtProperties = jwtProperties;
        // 키가 256bit보다 짧으면 jjwt가 WeakKeyException을 던져 기동 시점에 바로 알 수 있다.
        this.secretKey = Keys.hmacShaKeyFor(jwtProperties.secret().getBytes(StandardCharsets.UTF_8));
    }

    public IssuedToken createAccessToken(UUID userId) {
        return createToken(userId, TokenType.ACCESS, jwtProperties.accessTokenExpiration(), null);
    }

    // rememberMe는 재발급할 때도 같은 쿠키 모드(Max-Age 있음/세션 쿠키)를 이어 가려고 토큰에 담아 둔다.
    public IssuedToken createRefreshToken(UUID userId, boolean rememberMe) {
        return createToken(userId, TokenType.REFRESH, jwtProperties.refreshTokenExpiration(), rememberMe);
    }

    /**
     * 서명과 만료를 검증하고 claim을 돌려준다.
     * 만료면 ExpiredJwtException, 그 외 위조·형식 오류는 JwtException(또는 IllegalArgumentException)을 던진다.
     */
    public Claims parse(String token) {
        return Jwts.parser()
                .verifyWith(secretKey)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    public TokenType getTokenType(Claims claims) {
        String type = claims.get(TOKEN_TYPE_CLAIM, String.class);
        return type == null ? null : TokenType.valueOf(type);
    }

    public boolean isRememberMe(Claims claims) {
        return Boolean.TRUE.equals(claims.get(REMEMBER_ME_CLAIM, Boolean.class));
    }

    public Duration getAccessTokenExpiration() {
        return jwtProperties.accessTokenExpiration();
    }

    public Duration getRefreshTokenExpiration() {
        return jwtProperties.refreshTokenExpiration();
    }

    private IssuedToken createToken(UUID userId, TokenType type, Duration expiration, Boolean rememberMe) {
        String jti = UUID.randomUUID().toString();
        Instant now = Instant.now();
        Instant expiresAt = now.plus(expiration);

        JwtBuilder builder = Jwts.builder()
                .subject(userId.toString())
                .id(jti)
                .claim(TOKEN_TYPE_CLAIM, type.name())
                .issuedAt(Date.from(now))
                .expiration(Date.from(expiresAt));
        if (rememberMe != null) {
            builder.claim(REMEMBER_ME_CLAIM, rememberMe);
        }
        String token = builder.signWith(secretKey, Jwts.SIG.HS256).compact();

        return new IssuedToken(token, jti, expiresAt);
    }
}
