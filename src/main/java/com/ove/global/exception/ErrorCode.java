package com.ove.global.exception;

import org.springframework.http.HttpStatus;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * API 에러 코드. enum 이름이 그대로 응답의 error 값이 된다.
 */
@Getter
@RequiredArgsConstructor
public enum ErrorCode {

    // 400
    INVALID_REQUEST(HttpStatus.BAD_REQUEST, "요청 값이 올바르지 않습니다."),
    ORDER_AMOUNT_TOO_SMALL(HttpStatus.BAD_REQUEST, "주문 금액이 너무 작습니다."),

    // 401
    UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "인증이 필요합니다."),
    TOKEN_EXPIRED(HttpStatus.UNAUTHORIZED, "토큰이 만료되었습니다."),
    INVALID_CREDENTIALS(HttpStatus.UNAUTHORIZED, "이메일 또는 비밀번호가 올바르지 않습니다."),
    INVALID_REFRESH_TOKEN(HttpStatus.UNAUTHORIZED, "유효하지 않은 리프레시 토큰입니다."),

    // 403
    FORBIDDEN(HttpStatus.FORBIDDEN, "접근 권한이 없습니다."),

    // 404
    RESOURCE_NOT_FOUND(HttpStatus.NOT_FOUND, "요청한 리소스를 찾을 수 없습니다."),
    USER_NOT_FOUND(HttpStatus.NOT_FOUND, "사용자를 찾을 수 없습니다."),
    SIGNAL_NOT_FOUND(HttpStatus.NOT_FOUND, "신호 정보가 없습니다."),
    MARKET_NOT_FOUND(HttpStatus.NOT_FOUND, "종목을 찾을 수 없습니다."),
    PAPER_ACCOUNT_NOT_FOUND(HttpStatus.NOT_FOUND, "모의투자 계좌를 찾을 수 없습니다."),
    WATCHLIST_NOT_FOUND(HttpStatus.NOT_FOUND, "관심 종목에 없는 종목입니다."),

    // 405
    METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, "지원하지 않는 HTTP 메서드입니다."),

    // 409
    EMAIL_ALREADY_EXISTS(HttpStatus.CONFLICT, "이미 사용 중인 이메일입니다."),
    NICKNAME_ALREADY_EXISTS(HttpStatus.CONFLICT, "이미 사용 중인 닉네임입니다."),
    INSUFFICIENT_BALANCE(HttpStatus.CONFLICT, "주문 금액만큼의 모의투자 잔고가 없습니다."),
    INSUFFICIENT_HOLDING(HttpStatus.CONFLICT, "매도할 보유 수량이 부족합니다."),
    WATCHLIST_CONFLICT(HttpStatus.CONFLICT, "이미 등록된 종목이거나 최대 개수를 초과했습니다."),

    // 500
    INTERNAL_SERVER_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "서버 내부 오류가 발생했습니다."),

    // 502
    UPSTREAM_UNAVAILABLE(HttpStatus.BAD_GATEWAY, "외부 시세 서버에 연결할 수 없습니다. 잠시 후 다시 시도해 주세요."),

    // 503
    MARKET_PRICE_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "현재 시세를 조회할 수 없습니다."),
    MODEL_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "AI 모델 서버가 응답하지 않습니다. 마지막 신호를 유지합니다."),
    COLLECTOR_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "실시간 시세 수집이 중지되었거나 아직 시세를 받지 못했습니다.");

    private final HttpStatus status;
    private final String message;
}
