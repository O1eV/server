package com.ove.auth.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class LoginRequest {

    @NotBlank
    private String email;

    @NotBlank
    private String password;

    // 로그인 상태 유지. true면 refresh 쿠키가 14일 유지되고, 생략하거나 false면 브라우저를 닫을 때까지만 유지된다.
    private boolean rememberMe;
}
