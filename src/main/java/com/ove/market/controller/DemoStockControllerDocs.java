package com.ove.market.controller;

import java.time.Instant;

import org.springframework.http.ResponseEntity;

import com.ove.global.docs.ApiErrorCodes;
import com.ove.global.exception.ErrorCode;
import com.ove.global.response.ApiResponse;
import com.ove.market.dto.CandleListResponse;
import com.ove.market.dto.DemoStockPriceResponse;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * DemoStockController의 Swagger 문서(문서 전용 인터페이스). 발표용이라 키움 수집기가 켜진 로컬에서만 열린다.
 * 파라미터 제약(@Min 등)은 컨트롤러가 아니라 이 인터페이스에만 붙인다(HV000151 방지, ApiErrorCodes 설명 참고).
 */
@Tag(name = "Demo Stocks")
public interface DemoStockControllerDocs {

    @Operation(summary = "주식 시연 현재가 조회", description = """
            키움 수집기가 Redis에 저장한 주식 현재가를 돌려줍니다. **발표용**이며 `collector.kiwoom.enabled=true`이고 prod 프로필이 아닐 때만 열립니다(아니면 404).
            - `KIWOOM_SYMBOLS`에 설정한 6자리 코드만 조회할 수 있고, 아니면 `MARKET_NOT_FOUND`입니다.
            - 10초 넘게 갱신되지 않으면 `stale=true`입니다. 장 마감·미수신·Redis 장애는 `COLLECTOR_UNAVAILABLE`(503)입니다.
            """)
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "수집된 현재가")
    @ApiErrorCodes({ErrorCode.UNAUTHORIZED, ErrorCode.MARKET_NOT_FOUND, ErrorCode.COLLECTOR_UNAVAILABLE})
    ResponseEntity<ApiResponse<DemoStockPriceResponse>> getPrice(
            @Parameter(description = "종목 코드(6자리)", example = "005930") String code);

    @Operation(summary = "주식 시연 캔들 조회", description = """
            키움 수집기가 저장한 1분봉을 **시간 오름차순**으로 돌려줍니다. 열리는 조건은 현재가 조회와 같습니다.
            - 저장하는 단위가 1분봉뿐이라 `interval`은 `1m`만 받습니다.
            - `to` 이전(to 미포함)부터 과거로 `limit`개를 가져온 뒤 `from` 이후만 남깁니다. 생략하면 `to`는 지금입니다.
            - 해당 구간에 봉이 없으면 빈 목록입니다. DB 장애는 `COLLECTOR_UNAVAILABLE`(503)입니다.
            """)
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "시간 오름차순 1분봉(OHLCV)")
    @ApiErrorCodes({ErrorCode.INVALID_REQUEST, ErrorCode.UNAUTHORIZED, ErrorCode.MARKET_NOT_FOUND,
            ErrorCode.COLLECTOR_UNAVAILABLE})
    ResponseEntity<ApiResponse<CandleListResponse>> getCandles(
            @Parameter(description = "종목 코드(6자리)", example = "005930") String code,
            @Parameter(description = "봉 단위(1m만 지원)", example = "1m") String interval,
            @Parameter(description = "조회 시작 시각(UTC, ISO-8601, 포함)", example = "2026-10-26T00:00:00Z") Instant from,
            @Parameter(description = "조회 끝 기준 시각(UTC, 미포함). 생략하면 지금", example = "2026-10-26T06:30:00Z") Instant to,
            @Parameter(description = "최대 캔들 수(1~500)", example = "200") int limit);
}
