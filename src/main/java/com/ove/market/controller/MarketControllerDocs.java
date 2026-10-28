package com.ove.market.controller;

import java.time.Instant;

import org.springframework.http.ResponseEntity;

import com.ove.global.docs.ApiErrorCodes;
import com.ove.global.docs.ApiOptionalAuth;
import com.ove.global.exception.ErrorCode;
import com.ove.global.response.ApiResponse;
import com.ove.global.security.AuthUser;
import com.ove.market.dto.CandleListResponse;
import com.ove.market.dto.MarketListResponse;
import com.ove.market.dto.MarketSearchListResponse;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * MarketController의 Swagger 문서(문서 전용 인터페이스). 시세 조회는 로그인 없이 호출할 수 있다.
 * 파라미터 제약(@Min 등)은 컨트롤러가 아니라 이 인터페이스에만 붙인다(HV000151 방지, ApiErrorCodes 설명 참고).
 */
@Tag(name = "Markets")
public interface MarketControllerDocs {

    @Operation(summary = "종목 목록 조회", description = """
            지원하는 종목(현재 업비트 코인 KRW-BTC, KRW-ETH, KRW-XRP)을 필터링해 페이지 단위로 돌려줍니다.
            **로그인 없이 호출할 수 있고**, Bearer 토큰을 보내면 로그인 상태로 처리됩니다.
            - 로그인한 회원에게는 각 종목의 최신 `signal`·`confidence`를 합쳐서 돌려줍니다. 신호가 없으면 null입니다.
            - 비로그인 응답의 `signal`·`confidence`는 항상 null입니다.
            - `signal` 필터는 로그인한 회원만 쓸 수 있고, 최신 신호가 필터 값과 같은 종목만 남깁니다(비로그인이면 `UNAUTHORIZED`).
            - 필터 값은 앞뒤 공백을 무시하고 대소문자를 구분하지 않습니다(예: ` coin ` = `COIN`).
            - 필터를 모두 적용한 뒤 `page`·`size`로 자릅니다.
            - 현재가는 업비트에서 가져오며 최대 1.5초 전 값일 수 있습니다. 업비트가 응답하지 않거나 현재가·변동률이 빠져 있으면 `UPSTREAM_UNAVAILABLE`(502)입니다.
            """)
    @ApiOptionalAuth
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "조건에 맞는 종목 한 페이지와 페이지 정보")
    @ApiErrorCodes({ErrorCode.INVALID_REQUEST, ErrorCode.UNAUTHORIZED, ErrorCode.UPSTREAM_UNAVAILABLE})
    ResponseEntity<ApiResponse<MarketListResponse>> getMarkets(
            AuthUser authUser,
            @Parameter(description = "자산군 필터", schema = @io.swagger.v3.oas.annotations.media.Schema(
                    allowableValues = {"COIN", "KR_STOCK", "US_STOCK"})) String assetClass,
            @Parameter(description = "시장 상태 필터(전일 대비 변동률 기준)", schema = @io.swagger.v3.oas.annotations.media.Schema(
                    allowableValues = {"UP", "DOWN", "SIDEWAYS", "VOLATILE"})) String marketState,
            @Parameter(description = "최신 신호 필터. 로그인한 회원만 사용할 수 있습니다.", schema = @io.swagger.v3.oas.annotations.media.Schema(
                    allowableValues = {"BUY", "HOLD", "SELL"})) String signal,
            @Parameter(description = "페이지 번호(0부터 시작)", example = "0") int page,
            @Parameter(description = "페이지 크기(1~100)", example = "20") int size);

    @Operation(summary = "종목 검색", description = """
            한글 이름·영어 이름·심볼에 검색어가 들어간 종목을 찾습니다(대소문자 구분 없음). 로그인 없이 호출할 수 있습니다.
            - 검색어(`q`)는 필수입니다. 비어 있으면 `INVALID_REQUEST`입니다.
            """)
    @ApiOptionalAuth
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "검색어에 걸린 종목 목록(최대 limit개)")
    @ApiErrorCodes({ErrorCode.INVALID_REQUEST, ErrorCode.UPSTREAM_UNAVAILABLE})
    ResponseEntity<ApiResponse<MarketSearchListResponse>> searchMarkets(
            @Parameter(description = "검색어(한글 이름, 영어 이름 또는 심볼)", example = "비트", required = true) String q,
            @Parameter(description = "최대 결과 수(1~50)", example = "10") int limit);

    @Operation(summary = "캔들 조회", description = """
            종목의 차트용 캔들을 **시간 오름차순**으로 돌려줍니다. 로그인 없이 호출할 수 있습니다.
            - `to`부터 과거로 `limit`개를 가져온 뒤 `from` 이후만 남깁니다. `from`이 `to`보다 늦으면 `INVALID_REQUEST`입니다.
            - 업비트가 한 번에 200개까지만 주기 때문에 `limit`이 200을 넘으면 나눠서 받아 이어 붙입니다.
            - 체결이 없던 분의 캔들은 없습니다(채우지 않음).
            """)
    @ApiOptionalAuth
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "시간 오름차순 캔들 목록(OHLCV). 해당 구간에 체결이 없으면 빈 목록")
    @ApiErrorCodes({ErrorCode.INVALID_REQUEST, ErrorCode.MARKET_NOT_FOUND, ErrorCode.UPSTREAM_UNAVAILABLE})
    ResponseEntity<ApiResponse<CandleListResponse>> getCandles(
            @Parameter(description = "종목 심볼(현재 KRW-BTC, KRW-ETH, KRW-XRP). 지원하지 않으면 `MARKET_NOT_FOUND`", example = "KRW-BTC") String symbol,
            @Parameter(description = "봉 단위", example = "1m", schema = @io.swagger.v3.oas.annotations.media.Schema(
                    allowableValues = {"1m", "5m", "15m", "1h", "1d"}, defaultValue = "1m")) String interval,
            @Parameter(description = "조회 시작 시각(UTC, ISO-8601, 이 시각 포함)", example = "2026-10-24T00:00:00Z") Instant from,
            @Parameter(description = "조회 끝 기준 시각(UTC, ISO-8601, 이 시각은 포함하지 않고 그 이전 캔들부터). 생략하면 지금", example = "2026-10-24T03:00:00Z") Instant to,
            @Parameter(description = "최대 캔들 수(1~500)", example = "200") int limit);
}
