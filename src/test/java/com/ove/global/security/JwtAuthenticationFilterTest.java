package com.ove.global.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import java.time.Duration;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import com.ove.auth.repository.TokenRedisRepository;
import com.ove.global.exception.ErrorCode;

class JwtAuthenticationFilterTest {

    private static final String SECRET = "test-secret-key-for-ove-backend-must-be-at-least-32-bytes";

    private final JwtProvider jwtProvider = new JwtProvider(
            new JwtProperties(SECRET, Duration.ofMinutes(30), Duration.ofDays(14)));
    private final TokenRedisRepository tokenRedisRepository = mock(TokenRedisRepository.class);
    private final JwtAuthenticationFilter filter = new JwtAuthenticationFilter(jwtProvider, tokenRedisRepository);

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void 유효한_access_토큰이면_SecurityContext에_AuthUser를_채운다() throws Exception {
        UUID userId = UUID.randomUUID();
        IssuedToken token = jwtProvider.createAccessToken(userId);

        MockHttpServletRequest request = requestWithBearer(token.token());
        filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        assertThat(authentication).isNotNull();
        AuthUser authUser = (AuthUser) authentication.getPrincipal();
        assertThat(authUser.userId()).isEqualTo(userId);
        assertThat(authUser.accessTokenJti()).isEqualTo(token.jti());
        assertThat(request.getAttribute(JwtAuthenticationFilter.AUTH_ERROR_ATTRIBUTE)).isNull();
    }

    @Test
    void 토큰이_없으면_인증없이_통과한다() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, new MockHttpServletResponse(), chain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        assertThat(request.getAttribute(JwtAuthenticationFilter.AUTH_ERROR_ATTRIBUTE)).isNull();
        assertThat(chain.getRequest()).isNotNull();
    }

    @Test
    void 만료된_토큰이면_TOKEN_EXPIRED를_남긴다() throws Exception {
        JwtProvider expiredProvider = new JwtProvider(
                new JwtProperties(SECRET, Duration.ofSeconds(-10), Duration.ofDays(14)));
        MockHttpServletRequest request = requestWithBearer(
                expiredProvider.createAccessToken(UUID.randomUUID()).token());

        filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        assertThat(request.getAttribute(JwtAuthenticationFilter.AUTH_ERROR_ATTRIBUTE))
                .isEqualTo(ErrorCode.TOKEN_EXPIRED);
    }

    @Test
    void 블랙리스트_토큰이면_UNAUTHORIZED를_남긴다() throws Exception {
        IssuedToken token = jwtProvider.createAccessToken(UUID.randomUUID());
        given(tokenRedisRepository.isBlacklisted(token.jti())).willReturn(true);
        MockHttpServletRequest request = requestWithBearer(token.token());

        filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        assertThat(request.getAttribute(JwtAuthenticationFilter.AUTH_ERROR_ATTRIBUTE))
                .isEqualTo(ErrorCode.UNAUTHORIZED);
    }

    @Test
    void refresh_토큰을_access_자리에_쓰면_UNAUTHORIZED를_남긴다() throws Exception {
        MockHttpServletRequest request = requestWithBearer(
                jwtProvider.createRefreshToken(UUID.randomUUID(), false).token());

        filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        assertThat(request.getAttribute(JwtAuthenticationFilter.AUTH_ERROR_ATTRIBUTE))
                .isEqualTo(ErrorCode.UNAUTHORIZED);
    }

    @Test
    void 위조된_토큰이면_UNAUTHORIZED를_남긴다() throws Exception {
        MockHttpServletRequest request = requestWithBearer("forged.token.value");

        filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

        assertThat(request.getAttribute(JwtAuthenticationFilter.AUTH_ERROR_ATTRIBUTE))
                .isEqualTo(ErrorCode.UNAUTHORIZED);
    }

    private MockHttpServletRequest requestWithBearer(String token) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer " + token);
        return request;
    }
}
