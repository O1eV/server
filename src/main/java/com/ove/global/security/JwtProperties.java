package com.ove.global.security;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * application.yaml의 jwt.* 설정. 시크릿은 환경변수(JWT_SECRET)로만 주입한다.
 */
@Validated
@ConfigurationProperties(prefix = "jwt")
public record JwtProperties(
        @NotBlank String secret,
        @NotNull Duration accessTokenExpiration,
        @NotNull Duration refreshTokenExpiration
) {
}
