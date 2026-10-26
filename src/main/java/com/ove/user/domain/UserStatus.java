package com.ove.user.domain;

/**
 * 회원 상태. 탈퇴하면 행을 지우지 않고(soft delete) WITHDRAWN으로 바꾼다.
 */
public enum UserStatus {
    ACTIVE,
    WITHDRAWN
}
