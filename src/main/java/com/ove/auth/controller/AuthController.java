package com.ove.auth.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.ove.auth.dto.LoginRequest;
import com.ove.auth.dto.LoginResponse;
import com.ove.auth.dto.RefreshTokenRequest;
import com.ove.auth.dto.RegisterRequest;
import com.ove.auth.dto.RegisterResponse;
import com.ove.auth.dto.TokenResponse;
import com.ove.auth.service.AuthService;
import com.ove.global.response.ApiResponse;
import com.ove.global.security.AuthUser;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    @PostMapping("/register")
    public ResponseEntity<ApiResponse<RegisterResponse>> register(@Valid @RequestBody RegisterRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(HttpStatus.CREATED, "회원가입이 완료되었습니다.",
                        authService.register(request)));
    }

    @PostMapping("/login")
    public ResponseEntity<ApiResponse<LoginResponse>> login(@Valid @RequestBody LoginRequest request) {
        return ResponseEntity.ok(ApiResponse.success(HttpStatus.OK, "로그인되었습니다.", authService.login(request)));
    }

    @PostMapping("/refresh")
    public ResponseEntity<ApiResponse<TokenResponse>> refresh(@Valid @RequestBody RefreshTokenRequest request) {
        return ResponseEntity.ok(ApiResponse.success(HttpStatus.OK, "토큰이 재발급되었습니다.",
                authService.refresh(request.getRefreshToken())));
    }

    @PostMapping("/logout")
    public ResponseEntity<ApiResponse<Void>> logout(@AuthenticationPrincipal AuthUser authUser,
                                                    @Valid @RequestBody RefreshTokenRequest request) {
        authService.logout(authUser, request.getRefreshToken());
        return ResponseEntity.ok(ApiResponse.success(HttpStatus.OK, "로그아웃되었습니다.", null));
    }
}
