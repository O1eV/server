package com.ove.global.security;

import java.time.Duration;
import java.util.Optional;

import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;

/**
 * refresh 토큰 쿠키를 만들고 읽는다. 쿠키 속성은 모두 AuthCookieProperties에서 가져온다.
 * HttpOnly라서 프론트 JS는 값을 읽을 수 없고, 브라우저가 Path 안의 요청에만 자동으로 실어 보낸다.
 */
@Component
public class RefreshCookieFactory {

    private final AuthCookieProperties cookieProperties;
    private final Duration refreshTokenExpiration;

    public RefreshCookieFactory(AuthCookieProperties cookieProperties, JwtProperties jwtProperties) {
        this.cookieProperties = cookieProperties;
        this.refreshTokenExpiration = jwtProperties.refreshTokenExpiration();
    }

    /**
     * rememberMe가 true면 refresh 토큰 만료 시간만큼 유지되는 쿠키, false면 브라우저를 닫으면 사라지는
     * 세션 쿠키(Max-Age 없음)를 만든다.
     */
    public ResponseCookie create(String refreshToken, boolean rememberMe) {
        ResponseCookie.ResponseCookieBuilder builder = baseBuilder(refreshToken);
        if (rememberMe) {
            builder.maxAge(refreshTokenExpiration);
        }
        return builder.build();
    }

    // 지울 쿠키는 이름·경로·속성이 만들 때와 같아야 브라우저가 같은 쿠키로 보고 삭제한다.
    public ResponseCookie delete() {
        return baseBuilder("").maxAge(Duration.ZERO).build();
    }

    public Optional<String> read(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return Optional.empty();
        }
        for (Cookie cookie : cookies) {
            if (cookieProperties.name().equals(cookie.getName())) {
                return Optional.of(cookie.getValue());
            }
        }
        return Optional.empty();
    }

    private ResponseCookie.ResponseCookieBuilder baseBuilder(String value) {
        return ResponseCookie.from(cookieProperties.name(), value)
                .httpOnly(true)
                .secure(cookieProperties.secure())
                .sameSite(cookieProperties.sameSite())
                .path(cookieProperties.path());
    }
}
