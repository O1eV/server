package com.ove.auth.service;

/**
 * 로그인·재발급 결과. body는 응답 JSON으로, refreshToken은 HttpOnly 쿠키로 따로 내려간다.
 * rememberMe는 쿠키를 세션 쿠키로 할지(false) Max-Age를 줄지(true) 정할 때 쓴다.
 */
public record AuthResult<T>(T body, String refreshToken, boolean rememberMe) {
}
