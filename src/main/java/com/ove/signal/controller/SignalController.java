package com.ove.signal.controller;

import java.time.Instant;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.ove.global.response.ApiResponse;
import com.ove.signal.dto.SignalHistoryResponse;
import com.ove.signal.dto.SignalResponse;
import com.ove.signal.service.SignalService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;

@Tag(name = "Signals", description = "AI 판단 신호 조회")
@RestController
@RequestMapping("/api/signals")
@RequiredArgsConstructor
public class SignalController {

    private final SignalService signalService;

    @Operation(summary = "최신 신호 조회",
            description = "종목의 최신 AI 판단(BUY/HOLD/SELL 확률·확신도)을 돌려줍니다. "
                    + "마지막 신호가 2분보다 오래되면 stale=true입니다.")
    @GetMapping("/{symbol}")
    public ResponseEntity<ApiResponse<SignalResponse>> getLatest(
            @Parameter(description = "종목 심볼", example = "KRW-BTC") @PathVariable String symbol) {
        return ResponseEntity.ok(ApiResponse.success(HttpStatus.OK, "최신 신호 조회에 성공했습니다.",
                signalService.getLatest(symbol)));
    }

    @Operation(summary = "신호 이력 조회",
            description = "종목별 과거 신호 변화 이력을 기간별로 조회합니다. "
                    + "같은 신호가 이어지는 스냅샷은 제외하고 BUY/HOLD/SELL이 바뀐 시점만 최신순으로 돌려줍니다. "
                    + "기간을 생략하면 최근 7일, 최대 조회 기간은 90일입니다.")
    @GetMapping("/{symbol}/history")
    public ResponseEntity<ApiResponse<SignalHistoryResponse>> getHistory(
            @Parameter(description = "종목 심볼", example = "KRW-BTC") @PathVariable String symbol,
            @Parameter(description = "조회 시작 시각(UTC, ISO-8601, 포함)", example = "2026-10-20T00:00:00Z")
            @RequestParam(required = false) Instant from,
            @Parameter(description = "조회 끝 시각(UTC, ISO-8601, 포함). 생략하면 지금", example = "2026-10-26T00:00:00Z")
            @RequestParam(required = false) Instant to,
            @Parameter(description = "페이지 번호(0부터 시작)", example = "0") @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "페이지 크기(1~100)", example = "20") @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(ApiResponse.success(HttpStatus.OK, "신호 이력 조회에 성공했습니다.",
                signalService.getHistory(symbol, from, to, page, size)));
    }
}
