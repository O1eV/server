package com.ove.global.response;

import java.time.Instant;

import org.springframework.http.HttpStatus;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import com.ove.global.exception.ErrorCode;

import lombok.Builder;
import lombok.Getter;

/**
 * 모든 API가 공통으로 쓰는 응답 envelope.
 * 성공이면 error가 null, 실패면 data가 null이고 error에 UPPER_SNAKE_CASE 코드가 들어간다.
 */
@Getter
@Builder
public class ApiResponse<T> {

    private final int statusCode;
    private final Instant timestamp;
    private final String path;
    private final String message;
    private final T data;
    private final String error;

    public static <T> ApiResponse<T> success(HttpStatus status, String message, T data) {
        return ApiResponse.<T>builder()
                .statusCode(status.value())
                .timestamp(Instant.now())
                .path(currentPath())
                .message(message)
                .data(data)
                .build();
    }

    // 필터(Security) 단계에서는 RequestContextHolder가 비어 있을 수 있어 path를 직접 받는다.
    public static ApiResponse<Void> failure(ErrorCode errorCode, String message, String path) {
        return ApiResponse.<Void>builder()
                .statusCode(errorCode.getStatus().value())
                .timestamp(Instant.now())
                .path(path)
                .message(message)
                .error(errorCode.name())
                .build();
    }

    private static String currentPath() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes) {
            return attributes.getRequest().getRequestURI();
        }
        return null;
    }
}
