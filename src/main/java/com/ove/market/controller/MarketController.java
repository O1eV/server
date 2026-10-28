package com.ove.market.controller;

import java.time.Instant;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.ove.global.response.ApiResponse;
import com.ove.global.security.AuthUser;
import com.ove.market.dto.CandleListResponse;
import com.ove.market.dto.MarketListResponse;
import com.ove.market.dto.MarketSearchListResponse;
import com.ove.market.service.MarketService;

import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/markets")
@RequiredArgsConstructor
public class MarketController implements MarketControllerDocs {

    private static final String SUCCESS_MESSAGE = "요청에 성공했습니다.";

    private final MarketService marketService;

    // 종목 목록을 자산군·시장 상태·신호로 필터링해 조회할 때 사용 (signal 필터는 로그인 필요)
    @Override
    @GetMapping
    public ResponseEntity<ApiResponse<MarketListResponse>> getMarkets(
            @AuthenticationPrincipal AuthUser authUser,
            @RequestParam(required = false) String assetClass,
            @RequestParam(required = false) String marketState,
            @RequestParam(required = false) String signal,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        MarketListResponse response = marketService.getMarkets(assetClass, marketState, signal, page, size,
                authUser != null);
        return ResponseEntity.ok(ApiResponse.success(HttpStatus.OK, SUCCESS_MESSAGE, response));
    }

    // 종목을 한글 이름·영어 이름·심볼로 검색할 때 사용
    @Override
    @GetMapping("/search")
    public ResponseEntity<ApiResponse<MarketSearchListResponse>> searchMarkets(
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "10") int limit) {
        MarketSearchListResponse response = marketService.searchMarkets(q, limit);
        return ResponseEntity.ok(ApiResponse.success(HttpStatus.OK, SUCCESS_MESSAGE, response));
    }

    // 종목의 차트용 캔들을 조회할 때 사용
    @Override
    @GetMapping("/{symbol}/candles")
    public ResponseEntity<ApiResponse<CandleListResponse>> getCandles(
            @PathVariable String symbol,
            @RequestParam(defaultValue = "1m") String interval,
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to,
            @RequestParam(defaultValue = "200") int limit) {
        CandleListResponse response = marketService.getCandles(symbol, interval, from, to, limit);
        return ResponseEntity.ok(ApiResponse.success(HttpStatus.OK, SUCCESS_MESSAGE, response));
    }
}
