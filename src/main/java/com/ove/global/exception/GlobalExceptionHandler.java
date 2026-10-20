package com.ove.global.exception;

import java.util.stream.Collectors;

import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.client.RestClientException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import com.ove.global.response.ApiResponse;

import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;

/**
 * 컨트롤러에서 올라온 예외를 공통 envelope로 변환한다.
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResponse<Void>> handleBusiness(BusinessException e, HttpServletRequest request) {
        return toResponse(e.getErrorCode(), e.getMessage(), request);
    }

    // @Valid 검증 실패: 어떤 필드가 왜 틀렸는지 메시지에 모아 준다.
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleValidation(MethodArgumentNotValidException e,
                                                              HttpServletRequest request) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .collect(Collectors.joining(", "));
        return toResponse(ErrorCode.INVALID_REQUEST, message, request);
    }

    // JSON 형식이 깨졌거나 body가 없는 경우
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<Void>> handleNotReadable(HttpMessageNotReadableException e,
                                                               HttpServletRequest request) {
        return toResponse(ErrorCode.INVALID_REQUEST, "요청 본문을 읽을 수 없습니다.", request);
    }

    // 쿼리 파라미터 형식이 틀린 요청을 400으로 응답할 때 사용
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiResponse<Void>> handleTypeMismatch(MethodArgumentTypeMismatchException e,
                                                                HttpServletRequest request) {
        return toResponse(ErrorCode.INVALID_REQUEST, e.getName() + " 값의 형식이 올바르지 않습니다.", request);
    }

    // 필수 쿼리 파라미터가 빠진 요청을 400으로 응답할 때 사용
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ApiResponse<Void>> handleMissingParameter(MissingServletRequestParameterException e,
                                                                    HttpServletRequest request) {
        return toResponse(ErrorCode.INVALID_REQUEST, e.getParameterName() + " 값은 꼭 보내야 합니다.", request);
    }

    // 업비트 같은 외부 API 호출이 실패했을 때 503으로 응답할 때 사용
    @ExceptionHandler(RestClientException.class)
    public ResponseEntity<ApiResponse<Void>> handleRestClient(RestClientException e, HttpServletRequest request) {
        log.warn("External API call failed at {}: {}", request.getRequestURI(), e.getMessage());
        return toResponse(ErrorCode.MARKET_DATA_UNAVAILABLE, ErrorCode.MARKET_DATA_UNAVAILABLE.getMessage(), request);
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiResponse<Void>> handleNoResource(NoResourceFoundException e,
                                                              HttpServletRequest request) {
        return toResponse(ErrorCode.RESOURCE_NOT_FOUND, ErrorCode.RESOURCE_NOT_FOUND.getMessage(), request);
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiResponse<Void>> handleMethodNotSupported(HttpRequestMethodNotSupportedException e,
                                                                      HttpServletRequest request) {
        return toResponse(ErrorCode.METHOD_NOT_ALLOWED, ErrorCode.METHOD_NOT_ALLOWED.getMessage(), request);
    }

    // 예상하지 못한 예외는 내부 정보를 숨기고 500으로 응답한다.
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleUnexpected(Exception e, HttpServletRequest request) {
        log.error("Unhandled exception at {}", request.getRequestURI(), e);
        return toResponse(ErrorCode.INTERNAL_SERVER_ERROR, ErrorCode.INTERNAL_SERVER_ERROR.getMessage(), request);
    }

    private ResponseEntity<ApiResponse<Void>> toResponse(ErrorCode errorCode, String message,
                                                         HttpServletRequest request) {
        return ResponseEntity.status(errorCode.getStatus())
                .body(ApiResponse.failure(errorCode, message, request.getRequestURI()));
    }
}
