package com.ove.global.security;

import java.time.Instant;

/**
 * 발급한 토큰 문자열과 Redis 저장·블랙리스트에 필요한 jti, 만료 시각.
 */
public record IssuedToken(String token, String jti, Instant expiresAt) {
}
