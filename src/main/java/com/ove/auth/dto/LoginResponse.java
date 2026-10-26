package com.ove.auth.dto;

import com.ove.user.dto.UserSummaryResponse;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Getter;

@Schema(description = "로그인 응답. refreshToken은 본문이 아니라 Set-Cookie(HttpOnly)로 내려갑니다")
@Getter
@Builder
public class LoginResponse {

    @Schema(description = "Authorization: Bearer 헤더에 넣을 접근 토큰(JWT, 예시는 가짜 값)", example = "eyJhbGciOiJIUzI1NiJ9.example-payload.example-signature")
    private final String accessToken;
    // access 토큰 유효 시간(초)
    @Schema(description = "accessToken 유효 시간(초)", example = "1800")
    private final long expiresIn;
    @Schema(description = "로그인한 회원 정보")
    private final UserSummaryResponse user;
}
