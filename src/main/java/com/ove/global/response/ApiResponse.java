package com.ove.global.response;

import java.time.Instant;

import org.springframework.http.HttpStatus;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import com.ove.global.exception.ErrorCode;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Getter;

/**
 * 모든 API가 공통으로 쓰는 응답 envelope.
 * 성공이면 error가 null, 실패면 data가 null이고 error에 UPPER_SNAKE_CASE 코드가 들어간다.
 */
@Schema(description = "모든 API가 공통으로 쓰는 응답 envelope")
@Getter
@Builder
public class ApiResponse<T> {

    @Schema(description = "HTTP 상태 코드와 같은 값", example = "200")
    private final int statusCode;
    @Schema(description = "응답을 만든 시각(UTC, ISO-8601)", example = "2026-10-24T03:21:45.123Z")
    private final Instant timestamp;
    @Schema(description = "요청 경로", example = "/api/auth/login")
    private final String path;
    @Schema(description = "결과 메시지(한국어)", example = "요청에 성공했습니다.")
    private final String message;
    @Schema(description = "성공하면 응답 본문, 실패하면 null")
    private final T data;
    @Schema(description = "실패하면 UPPER_SNAKE_CASE 에러 코드, 성공하면 null")
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
