package com.ove.auth.dto;

import com.ove.user.dto.UserSummaryResponse;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Getter;

@Schema(description = "회원가입 응답. 토큰은 없습니다(자동 로그인 안 함)")
@Getter
@Builder
public class RegisterResponse {

    @Schema(description = "가입한 회원 정보")
    private final UserSummaryResponse user;
}
