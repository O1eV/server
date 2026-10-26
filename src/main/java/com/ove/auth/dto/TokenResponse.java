package com.ove.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Getter;

@Schema(description = "토큰 재발급 응답. 새 refreshToken은 본문이 아니라 Set-Cookie(HttpOnly)로 내려갑니다")
@Getter
@Builder
public class TokenResponse {

    @Schema(description = "새 접근 토큰(JWT, 예시는 가짜 값)", example = "eyJhbGciOiJIUzI1NiJ9.example-payload.example-signature")
    private final String accessToken;
    // access 토큰 유효 시간(초)
    @Schema(description = "accessToken 유효 시간(초)", example = "1800")
    private final long expiresIn;
}
