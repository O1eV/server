package com.ove.global.security;

/**
 * 토큰 종류. refresh 토큰을 access 토큰 자리에 쓰지 못하게 claim으로 구분한다.
 */
public enum TokenType {
    ACCESS,
    REFRESH
}
