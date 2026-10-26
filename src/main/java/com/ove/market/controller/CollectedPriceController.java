package com.ove.market.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import com.ove.global.response.ApiResponse;
import com.ove.market.dto.CollectedPriceResponse;
import com.ove.market.service.CollectedPriceService;
import io.swagger.v3.oas.annotations.Operation;
import lombok.RequiredArgsConstructor;

@RestController
@RequiredArgsConstructor
public class CollectedPriceController {
    private final CollectedPriceService service;

    @Operation(summary = "수집된 최신 현재가 조회", description = "Redis의 수집 시세. 10초 이상 갱신되지 않으면 stale=true, 수집 중지·미수신은 503.")
    @GetMapping("/api/markets/{symbol}/price")
    public ResponseEntity<ApiResponse<CollectedPriceResponse>> getPrice(@PathVariable String symbol) {
        return ResponseEntity.ok().cacheControl(org.springframework.http.CacheControl.noStore())
                .body(ApiResponse.success(HttpStatus.OK, "수집 시세를 조회했습니다.", service.getPrice(symbol)));
    }
}
