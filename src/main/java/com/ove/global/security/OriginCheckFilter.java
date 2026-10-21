package com.ove.global.security;

import java.io.IOException;
import java.util.Set;

import org.springframework.http.HttpMethod;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;

/**
 * 쿠키로 인증하는 refresh·logout 요청의 CSRF 방어.
 * SameSite=Lax 쿠키에 더해, Origin 헤더가 있으면 허용 origin 목록과 맞는지 확인하고 아니면 403으로 막는다.
 * Origin 헤더가 없는 요청(서버 간 호출, 테스트 등)은 브라우저의 교차 출처 요청이 아니므로 통과시킨다.
 */
@RequiredArgsConstructor
public class OriginCheckFilter extends OncePerRequestFilter {

    private static final Set<String> PROTECTED_PATHS = Set.of("/api/auth/refresh", "/api/auth/logout");

    private final CorsProperties corsProperties;
    private final AccessDeniedHandler accessDeniedHandler;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !HttpMethod.POST.matches(request.getMethod()) || !PROTECTED_PATHS.contains(request.getRequestURI());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String origin = request.getHeader("Origin");
        if (origin != null && !corsProperties.allowedOrigins().contains(origin)) {
            accessDeniedHandler.handle(request, response, new AccessDeniedException("허용되지 않은 Origin입니다."));
            return;
        }
        filterChain.doFilter(request, response);
    }
}
