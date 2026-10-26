package com.ove.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Schema(description = "로그인 요청")
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class LoginRequest {

    @NotBlank
    @Schema(description = "가입한 이메일", example = "user@example.com")
    private String email;

    @NotBlank
    @Schema(description = "비밀번호", example = "Passw0rd123")
    private String password;

    // 로그인 상태 유지. true면 refresh 쿠키가 14일 유지되고, 생략하거나 false면 브라우저를 닫을 때까지만 유지된다.
    @Schema(description = "로그인 상태 유지. true면 refresh 쿠키가 14일 유지되고, 생략하거나 false면 브라우저를 닫을 때까지만 유지됩니다", example = "false", defaultValue = "false")
    private boolean rememberMe;
}
