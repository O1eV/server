package com.ove.market.controller;

import java.time.Instant;
import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.ove.global.response.ApiResponse;
import com.ove.market.dto.CandleResponse;
import com.ove.market.dto.MarketResponse;
import com.ove.market.dto.MarketSearchResponse;
import com.ove.market.service.MarketService;

import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/markets")
@RequiredArgsConstructor
public class MarketController {

    private final MarketService marketService;

    // 종목 목록을 assetClass, marketState, sign 필터로 조회할 때 사용
    @GetMapping
    public ResponseEntity<ApiResponse<List<MarketResponse>>> getMarkets(
            @RequestParam(required = false) String assetClass,
            @RequestParam(required = false) String marketState,
            @RequestParam(required = false) String sign) {
        List<MarketResponse> response = marketService.getMarkets(assetClass, marketState, sign);
        return ResponseEntity.ok(ApiResponse.success(HttpStatus.OK, "종목 목록 조회 성공", response));
    }

    // 종목을 코드나 이름으로 검색할 때 사용
    @GetMapping("/search")
    public ResponseEntity<ApiResponse<List<MarketSearchResponse>>> searchMarkets(
            @RequestParam String q,
            @RequestParam(defaultValue = "10") int limit) {
        List<MarketSearchResponse> response = marketService.searchMarkets(q, limit);
        return ResponseEntity.ok(ApiResponse.success(HttpStatus.OK, "종목 검색 성공", response));
    }

    // 종목의 차트용 캔들을 조회할 때 사용
    @GetMapping("/{symbol}/candles")
    public ResponseEntity<ApiResponse<List<CandleResponse>>> getCandles(
            @PathVariable String symbol,
            @RequestParam(defaultValue = "1m") String interval,
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to,
            @RequestParam(defaultValue = "200") int limit) {
        List<CandleResponse> response = marketService.getCandles(symbol, interval, from, to, limit);
        return ResponseEntity.ok(ApiResponse.success(HttpStatus.OK, "캔들 조회 성공", response));
    }
}
