package com.ove.market.controller;

import java.time.Instant;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.ove.global.response.ApiResponse;
import com.ove.market.dto.CandleListResponse;
import com.ove.market.dto.DemoStockPriceResponse;
import com.ove.market.service.DemoStockService;

import lombok.RequiredArgsConstructor;

/**
 * 발표용 주식 시세 조회. 키움 수집기가 켜져 있고 prod가 아닐 때만 등록되어, 아니면 매핑이 없어 404다.
 */
@RestController
@RequestMapping("/api/demo/stocks")
@ConditionalOnProperty(prefix = "collector.kiwoom", name = "enabled", havingValue = "true")
@Profile("!prod")
@RequiredArgsConstructor
public class DemoStockController implements DemoStockControllerDocs {

    private final DemoStockService service;

    // 수집된 주식 현재가를 조회할 때 사용
    @Override
    @GetMapping("/{code}/price")
    public ResponseEntity<ApiResponse<DemoStockPriceResponse>> getPrice(@PathVariable String code) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(ApiResponse.success(HttpStatus.OK, "수집 시세를 조회했습니다.", service.getPrice(code)));
    }

    // 저장된 주식 1분봉을 조회할 때 사용
    @Override
    @GetMapping("/{code}/candles")
    public ResponseEntity<ApiResponse<CandleListResponse>> getCandles(
            @PathVariable String code,
            @RequestParam(defaultValue = "1m") String interval,
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to,
            @RequestParam(defaultValue = "200") int limit) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(ApiResponse.success(HttpStatus.OK, "수집 캔들을 조회했습니다.", service.getCandles(code, interval, from, to, limit)));
    }
}
