package com.ove.global.security;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * application.yaml의 auth.cookie.* 설정. refresh 토큰을 담는 쿠키의 이름·경로·보안 속성.
 * 로컬 http 개발 환경에서만 secure를 끄고, 그 외에는 기본값(true)을 유지한다.
 */
@ConfigurationProperties(prefix = "auth.cookie")
public record AuthCookieProperties(
        @DefaultValue("refreshToken") String name,
        @DefaultValue("/api/auth") String path,
        @DefaultValue("true") boolean secure,
        @DefaultValue("Lax") String sameSite
) {
}
