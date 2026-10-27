package com.ove.signal.controller;

import java.time.Instant;

import org.springframework.http.ResponseEntity;

import com.ove.global.docs.ApiErrorCodes;
import com.ove.global.exception.ErrorCode;
import com.ove.global.response.ApiResponse;
import com.ove.signal.dto.SignalHistoryResponse;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * SignalController의 Swagger 문서(문서 전용 인터페이스). 현재는 이력 조회만 옮겨 두었다.
 * 파라미터 제약(@Min 등)은 컨트롤러가 아니라 이 인터페이스에만 붙인다(HV000151 방지, ApiErrorCodes 설명 참고).
 */
@Tag(name = "Signals")
public interface SignalControllerDocs {

    @Operation(summary = "신호 이력 조회", description = """
            종목의 AI 신호 **전환 이력**을 최신순으로 돌려줍니다. 인증이 필요합니다.
            - 신호가 바뀐 시점(`previousSignal`이 있는 신호)만 담고, 같은 신호가 이어진 기록은 제외합니다.
            - `from`·`to`를 생략하면 최근 7일입니다. 조회 기간은 최대 90일이고, 넘기거나 `from`이 `to`보다 늦으면 `INVALID_REQUEST`입니다.
            - 지원하지 않는 종목은 `MARKET_NOT_FOUND`입니다. 이력이 없으면 빈 목록(200)입니다.
            """)
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "전환 이력 한 페이지(최신순)와 페이지 정보")
    @ApiErrorCodes({ErrorCode.INVALID_REQUEST, ErrorCode.UNAUTHORIZED, ErrorCode.MARKET_NOT_FOUND})
    ResponseEntity<ApiResponse<SignalHistoryResponse>> history(
            @Parameter(description = "종목 심볼(현재 KRW-BTC, KRW-ETH, KRW-XRP)", example = "KRW-BTC") String symbol,
            @Parameter(description = "조회 시작 시각(UTC, ISO-8601, 포함). 생략하면 to에서 7일 전", example = "2026-10-20T00:00:00Z") Instant from,
            @Parameter(description = "조회 끝 시각(UTC, ISO-8601, 포함). 생략하면 지금", example = "2026-10-26T00:00:00Z") Instant to,
            @Parameter(description = "페이지 번호(0부터 시작)", example = "0") int page,
            @Parameter(description = "페이지 크기(1~100)", example = "20") int size);
}
