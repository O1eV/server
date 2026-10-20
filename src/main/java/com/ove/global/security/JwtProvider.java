package com.ove.global.security;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

import javax.crypto.SecretKey;

import org.springframework.stereotype.Component;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

/**
 * HS256 JWT 발급·검증. claim은 sub=userId, jti, type(ACCESS/REFRESH)만 쓴다.
 */
@Component
public class JwtProvider {

    private static final String TOKEN_TYPE_CLAIM = "type";

    private final JwtProperties jwtProperties;
    private final SecretKey secretKey;

    public JwtProvider(JwtProperties jwtProperties) {
        this.jwtProperties = jwtProperties;
        // 키가 256bit보다 짧으면 jjwt가 WeakKeyException을 던져 기동 시점에 바로 알 수 있다.
        this.secretKey = Keys.hmacShaKeyFor(jwtProperties.secret().getBytes(StandardCharsets.UTF_8));
    }

    public IssuedToken createAccessToken(UUID userId) {
        return createToken(userId, TokenType.ACCESS, jwtProperties.accessTokenExpiration());
    }

    public IssuedToken createRefreshToken(UUID userId) {
        return createToken(userId, TokenType.REFRESH, jwtProperties.refreshTokenExpiration());
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

    public Duration getAccessTokenExpiration() {
        return jwtProperties.accessTokenExpiration();
    }

    public Duration getRefreshTokenExpiration() {
        return jwtProperties.refreshTokenExpiration();
    }

    private IssuedToken createToken(UUID userId, TokenType type, Duration expiration) {
        String jti = UUID.randomUUID().toString();
        Instant now = Instant.now();
        Instant expiresAt = now.plus(expiration);

        String token = Jwts.builder()
                .subject(userId.toString())
                .id(jti)
                .claim(TOKEN_TYPE_CLAIM, type.name())
                .issuedAt(Date.from(now))
                .expiration(Date.from(expiresAt))
                .signWith(secretKey, Jwts.SIG.HS256)
                .compact();

        return new IssuedToken(token, jti, expiresAt);
    }
}
