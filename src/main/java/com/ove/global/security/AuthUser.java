package com.ove.global.security;

import java.time.Instant;
import java.util.UUID;

/**
 * SecurityContext에 담기는 인증 주체.
 * 로그아웃 때 access 토큰을 블랙리스트에 넣어야 하므로 jti와 만료 시각을 함께 들고 있다.
 */
public record AuthUser(UUID userId, String accessTokenJti, Instant accessTokenExpiresAt) {
}
