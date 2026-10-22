package com.ove.global.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseCookie;
import org.springframework.mock.web.MockHttpServletRequest;

import jakarta.servlet.http.Cookie;

class RefreshCookieFactoryTest {

    private static final JwtProperties JWT = new JwtProperties(
            "test-secret-key-for-ove-backend-must-be-at-least-32-bytes", Duration.ofMinutes(30), Duration.ofDays(14));

    @Test
    void 기본_속성은_HttpOnly_Secure_Lax_경로는_api_auth() {
        RefreshCookieFactory factory = new RefreshCookieFactory(
                new AuthCookieProperties("refreshToken", "/api/auth", true, "Lax"), JWT);

        ResponseCookie cookie = factory.create("token-value", true);

        assertThat(cookie.getName()).isEqualTo("refreshToken");
        assertThat(cookie.getValue()).isEqualTo("token-value");
        assertThat(cookie.isHttpOnly()).isTrue();
        assertThat(cookie.isSecure()).isTrue();
        assertThat(cookie.getSameSite()).isEqualTo("Lax");
        assertThat(cookie.getPath()).isEqualTo("/api/auth");
    }

    @Test
    void rememberMe면_refresh_만료시간_14일_Max_Age_아니면_세션_쿠키() {
        RefreshCookieFactory factory = new RefreshCookieFactory(
                new AuthCookieProperties("refreshToken", "/api/auth", true, "Lax"), JWT);

        assertThat(factory.create("t", true).getMaxAge()).isEqualTo(Duration.ofSeconds(1209600));
        // Max-Age가 없으면 ResponseCookie는 음수(-1초)로 표현하고 헤더에도 쓰지 않는다.
        ResponseCookie session = factory.create("t", false);
        assertThat(session.getMaxAge().isNegative()).isTrue();
        assertThat(session.toString()).doesNotContain("Max-Age");
    }

    @Test
    void 설정값을_바꾸면_쿠키_속성에_그대로_반영된다() {
        RefreshCookieFactory factory = new RefreshCookieFactory(
                new AuthCookieProperties("rt", "/auth", false, "Strict"), JWT);

        ResponseCookie cookie = factory.create("t", true);

        assertThat(cookie.getName()).isEqualTo("rt");
        assertThat(cookie.getPath()).isEqualTo("/auth");
        assertThat(cookie.isSecure()).isFalse();
        assertThat(cookie.getSameSite()).isEqualTo("Strict");
        assertThat(cookie.isHttpOnly()).isTrue();
    }

    @Test
    void 삭제_쿠키는_같은_이름_경로_속성에_Max_Age_0() {
        RefreshCookieFactory factory = new RefreshCookieFactory(
                new AuthCookieProperties("refreshToken", "/api/auth", true, "Lax"), JWT);

        ResponseCookie deleted = factory.delete();

        assertThat(deleted.getName()).isEqualTo("refreshToken");
        assertThat(deleted.getValue()).isEmpty();
        assertThat(deleted.getMaxAge()).isEqualTo(Duration.ZERO);
        assertThat(deleted.getPath()).isEqualTo("/api/auth");
        assertThat(deleted.isHttpOnly()).isTrue();
        assertThat(deleted.isSecure()).isTrue();
        assertThat(deleted.getSameSite()).isEqualTo("Lax");
    }

    @Test
    void 요청에서_설정된_이름의_쿠키만_읽는다() {
        RefreshCookieFactory factory = new RefreshCookieFactory(
                new AuthCookieProperties("rt", "/api/auth", true, "Lax"), JWT);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setCookies(new Cookie("refreshToken", "other"), new Cookie("rt", "mine"));

        assertThat(factory.read(request)).contains("mine");
        assertThat(factory.read(new MockHttpServletRequest())).isEmpty();
    }
}
