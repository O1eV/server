package com.ove.global.security;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import com.ove.auth.repository.TokenRedisRepository;
import com.ove.global.exception.ErrorCode;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;

/**
 * Authorization: Bearer 토큰을 검증해 SecurityContext를 채운다.
 *
 * 토큰이 잘못돼도 여기서 바로 401을 내지 않고 실패 사유만 request attribute에 남긴다.
 * 그래야 permitAll 경로는 익명으로 통과하고, 인증이 필요한 경로에서만
 * JwtAuthenticationEntryPoint가 사유(TOKEN_EXPIRED / UNAUTHORIZED)에 맞춰 응답한다.
 */
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    public static final String AUTH_ERROR_ATTRIBUTE = "ove.authError";
    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtProvider jwtProvider;
    private final TokenRedisRepository tokenRedisRepository;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String token = resolveToken(request);
        if (token != null) {
            authenticate(request, token);
        }
        filterChain.doFilter(request, response);
    }

    private void authenticate(HttpServletRequest request, String token) {
        try {
            Claims claims = jwtProvider.parse(token);
            if (jwtProvider.getTokenType(claims) != TokenType.ACCESS
                    || tokenRedisRepository.isBlacklisted(claims.getId())) {
                request.setAttribute(AUTH_ERROR_ATTRIBUTE, ErrorCode.UNAUTHORIZED);
                return;
            }

            AuthUser authUser = new AuthUser(UUID.fromString(claims.getSubject()), claims.getId(),
                    claims.getExpiration().toInstant());
            UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                    authUser, null, List.of(new SimpleGrantedAuthority("ROLE_USER")));
            SecurityContextHolder.getContext().setAuthentication(authentication);
        } catch (ExpiredJwtException e) {
            request.setAttribute(AUTH_ERROR_ATTRIBUTE, ErrorCode.TOKEN_EXPIRED);
        } catch (JwtException | IllegalArgumentException e) {
            // 서명 위조, 형식 오류, 잘못된 sub/type 값 등
            request.setAttribute(AUTH_ERROR_ATTRIBUTE, ErrorCode.UNAUTHORIZED);
        }
    }

    private String resolveToken(HttpServletRequest request) {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header != null && header.startsWith(BEARER_PREFIX)) {
            String token = header.substring(BEARER_PREFIX.length()).trim();
            return token.isEmpty() ? null : token;
        }
        return null;
    }
}
