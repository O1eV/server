package com.ove.auth.dto;

import com.ove.user.dto.UserSummaryResponse;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class LoginResponse {

    private final String accessToken;
    private final String refreshToken;
    // access 토큰 유효 시간(초)
    private final long expiresIn;
    private final UserSummaryResponse user;
}
