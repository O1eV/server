package com.ove.global.exception;

import lombok.Getter;

/**
 * 서비스 계층에서 던지는 비즈니스 예외. GlobalExceptionHandler가 공통 envelope로 바꾼다.
 */
@Getter
public class BusinessException extends RuntimeException {

    private final ErrorCode errorCode;

    public BusinessException(ErrorCode errorCode) {
        super(errorCode.getMessage());
        this.errorCode = errorCode;
    }

    // 같은 코드라도 상황별로 더 구체적인 메시지를 내려 줄 때 쓴다.
    public BusinessException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }
}
