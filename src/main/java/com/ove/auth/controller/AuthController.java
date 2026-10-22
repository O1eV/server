package com.ove.auth.controller;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.ove.auth.dto.LoginRequest;
import com.ove.auth.dto.LoginResponse;
import com.ove.auth.dto.RegisterRequest;
import com.ove.auth.dto.RegisterResponse;
import com.ove.auth.dto.TokenResponse;
import com.ove.auth.service.AuthResult;
import com.ove.auth.service.AuthService;
import com.ove.global.response.ApiResponse;
import com.ove.global.security.AuthUser;
import com.ove.global.security.RefreshCookieFactory;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;
    private final RefreshCookieFactory refreshCookieFactory;

    @PostMapping("/register")
    public ResponseEntity<ApiResponse<RegisterResponse>> register(@Valid @RequestBody RegisterRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(HttpStatus.CREATED, "회원가입이 완료되었습니다.",
                        authService.register(request)));
    }

    // refresh 토큰은 응답 본문에 넣지 않고 HttpOnly 쿠키(Set-Cookie)로만 내려준다.
    @PostMapping("/login")
    public ResponseEntity<ApiResponse<LoginResponse>> login(@Valid @RequestBody LoginRequest request) {
        AuthResult<LoginResponse> result = authService.login(request);
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE,
                        refreshCookieFactory.create(result.refreshToken(), result.rememberMe()).toString())
                .body(ApiResponse.success(HttpStatus.OK, "로그인되었습니다.", result.body()));
    }

    // 요청 본문 없이 쿠키의 refresh 토큰으로 재발급하고, 쿠키도 새 토큰으로 교체한다.
    @PostMapping("/refresh")
    public ResponseEntity<ApiResponse<TokenResponse>> refresh(HttpServletRequest request) {
        String refreshToken = refreshCookieFactory.read(request).orElse(null);
        AuthResult<TokenResponse> result = authService.refresh(refreshToken);
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE,
                        refreshCookieFactory.create(result.refreshToken(), result.rememberMe()).toString())
                .body(ApiResponse.success(HttpStatus.OK, "토큰이 재발급되었습니다.", result.body()));
    }

    // 쿠키가 없거나 잘못돼도 access 토큰 블랙리스트 처리 후 200으로 끝내고, 쿠키는 항상 지운다.
    @PostMapping("/logout")
    public ResponseEntity<ApiResponse<Void>> logout(@AuthenticationPrincipal AuthUser authUser,
                                                    HttpServletRequest request) {
        authService.logout(authUser, refreshCookieFactory.read(request).orElse(null));
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, refreshCookieFactory.delete().toString())
                .body(ApiResponse.success(HttpStatus.OK, "로그아웃되었습니다.", null));
    }
}
