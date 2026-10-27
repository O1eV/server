package com.ove.paper.controller;

import org.springframework.http.ResponseEntity;

import com.ove.global.docs.ApiErrorCodes;
import com.ove.global.exception.ErrorCode;
import com.ove.global.response.ApiResponse;
import com.ove.global.security.AuthUser;
import com.ove.paper.dto.PaperOrderHistoryResponse;
import com.ove.paper.dto.PaperOrderRequest;
import com.ove.paper.dto.PaperOrderResponse;
import com.ove.paper.dto.PaperPortfolioResponse;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/**
 * PaperTradingController의 Swagger 문서(문서 전용 인터페이스).
 * 파라미터 제약(@Min 등)은 컨트롤러가 아니라 이 인터페이스에만 붙인다(HV000151 방지, ApiErrorCodes 설명 참고).
 */
@Tag(name = "Paper")
public interface PaperTradingControllerDocs {

    @Operation(summary = "모의 계좌 초기화", description = """
            보유 종목과 주문 이력을 모두 지우고 현금 잔고를 최초 금액(10,000,000 KRW)으로 되돌립니다.
            상장폐지·거래 중단으로 시세가 없는 보유 종목도 이 API로 정리할 수 있습니다.
            """)
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "초기화된 모의 포트폴리오")
    @ApiErrorCodes({ErrorCode.TOKEN_EXPIRED, ErrorCode.UNAUTHORIZED, ErrorCode.PAPER_ACCOUNT_NOT_FOUND})
    ResponseEntity<ApiResponse<PaperPortfolioResponse>> reset(AuthUser authUser);

    @Operation(summary = "모의 포트폴리오 조회", description = """
            현금 잔고와 보유 종목의 평가 금액·손익·수익률을 돌려줍니다.
            - 계좌와 보유 종목은 같은 시점에 읽고, 현재가는 그 뒤에 조회합니다.
            - 일부 종목의 시세를 못 구하면 그 종목만 평균 매수가로 평가하고 `pricesComplete=false`,
              종목별 `priceAvailable=false`로 알려 줍니다. 이때 총 평가 금액과 종목별 금액은 서로 맞습니다.
            """)
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "현금·보유 종목·평가 손익")
    @ApiErrorCodes({ErrorCode.TOKEN_EXPIRED, ErrorCode.UNAUTHORIZED, ErrorCode.PAPER_ACCOUNT_NOT_FOUND})
    ResponseEntity<ApiResponse<PaperPortfolioResponse>> getPortfolio(AuthUser authUser);

    @Operation(summary = "모의 거래 내역 조회", description = """
            체결된 모의 주문을 최신순으로 돌려줍니다. `symbol`을 주면 해당 종목만 필터링합니다.
            같은 시각에 체결된 주문은 주문 id를 보조 정렬로 두어 페이지 경계에서 중복·누락되지 않게 합니다.
            """)
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "주문 내역 한 페이지와 페이지 정보")
    @ApiErrorCodes({ErrorCode.INVALID_REQUEST, ErrorCode.TOKEN_EXPIRED, ErrorCode.UNAUTHORIZED})
    ResponseEntity<ApiResponse<PaperOrderHistoryResponse>> getOrders(
            AuthUser authUser,
            @Parameter(description = "종목 심볼. 생략하면 전체", example = "KRW-BTC") String symbol,
            @Parameter(description = "페이지 번호(0부터 시작)", example = "0") @Min(0) int page,
            @Parameter(description = "페이지 크기(1~100)", example = "20") @Min(1) @Max(100) int size);

    @Operation(summary = "모의 주문", description = """
            서버가 업비트에서 조회한 최신가로 즉시 체결합니다. 요청에 가격을 넣을 수 없습니다.
            - 거래소 종목 목록에 없는 종목(상장폐지·거래 중단)은 보유 중이어도 **매도할 수 없습니다**.
              `MARKET_NOT_FOUND`(404)이며, 해당 보유 종목은 모의 계좌 초기화(`POST /api/paper/reset`)로만 정리할 수 있습니다.
            - 업비트 장애로 종목 확인이나 현재가 조회를 못 하면 `MARKET_PRICE_UNAVAILABLE`(503)입니다.
            - 반올림 후 주문 금액이 0이면 `ORDER_AMOUNT_TOO_SMALL`(400)입니다.
            """)
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "체결된 모의 주문")
    @ApiErrorCodes({ErrorCode.INVALID_REQUEST, ErrorCode.TOKEN_EXPIRED, ErrorCode.UNAUTHORIZED,
            ErrorCode.MARKET_NOT_FOUND, ErrorCode.MARKET_PRICE_UNAVAILABLE, ErrorCode.ORDER_AMOUNT_TOO_SMALL,
            ErrorCode.INSUFFICIENT_BALANCE, ErrorCode.INSUFFICIENT_HOLDING, ErrorCode.PAPER_ACCOUNT_NOT_FOUND})
    ResponseEntity<ApiResponse<PaperOrderResponse>> placeOrder(AuthUser authUser, PaperOrderRequest request);
}
