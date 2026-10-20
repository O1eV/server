package com.ove.global.security;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

import com.ove.auth.repository.TokenRedisRepository;

import lombok.RequiredArgsConstructor;

/**
 * 세션 없는(stateless) JWT 인증 설정.
 * 로그인 없이 열어 두는 경로만 permitAll로 나열하고, 나머지는 모두 Bearer 토큰을 요구한다.
 */
@Configuration
@EnableWebSecurity
@EnableConfigurationProperties(JwtProperties.class)
@RequiredArgsConstructor
public class SecurityConfig {

    private static final String[] PUBLIC_POST_PATHS = {
            "/api/auth/register",
            "/api/auth/login",
            "/api/auth/refresh"
    };

    // 시세·종목 조회는 비로그인도 볼 수 있다. 토큰이 있으면 필터가 인증 정보를 채워 둔다.
    private static final String[] PUBLIC_GET_PATHS = {
            "/api/markets",
            "/api/markets/search",
            "/api/markets/*/candles"
    };

    private static final String[] SWAGGER_PATHS = {
            "/swagger-ui.html",
            "/swagger-ui/**",
            "/v3/api-docs/**"
    };

    private final JwtProvider jwtProvider;
    private final TokenRedisRepository tokenRedisRepository;
    private final JwtAuthenticationEntryPoint authenticationEntryPoint;
    private final JwtAccessDeniedHandler accessDeniedHandler;

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(exception -> exception
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.POST, PUBLIC_POST_PATHS).permitAll()
                        .requestMatchers(HttpMethod.GET, PUBLIC_GET_PATHS).permitAll()
                        .requestMatchers(SWAGGER_PATHS).permitAll()
                        // 컨테이너 에러 디스패치가 401로 덮이지 않도록 연다.
                        .requestMatchers("/error").permitAll()
                        .anyRequest().authenticated())
                // 필터를 Bean으로 등록하면 서블릿 필터로도 한 번 더 등록되므로 여기서 직접 생성한다.
                .addFilterBefore(new JwtAuthenticationFilter(jwtProvider, tokenRedisRepository),
                        UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
