package com.ove.auth.dto;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class TokenResponse {

    private final String accessToken;
    private final String refreshToken;
    // access 토큰 유효 시간(초)
    private final long expiresIn;
}
