package com.ove.signal.controller;

import java.time.Instant;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import com.ove.global.response.ApiResponse;
import com.ove.signal.dto.LatestSignalResponse;
import com.ove.signal.dto.SignalHistoryResponse;
import com.ove.signal.service.SignalService;
import io.swagger.v3.oas.annotations.Operation;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/signals")
@RequiredArgsConstructor
public class SignalController implements SignalControllerDocs {
    private final SignalService service;
    @Operation(summary = "최신 AI 신호 조회", description = "인증 필요. 생성 후 2분 이상 지난 신호는 stale=true. 미생성 시 404 SIGNAL_NOT_FOUND.")
    @GetMapping("/{symbol}")
    public ApiResponse<LatestSignalResponse> latest(@PathVariable String symbol) {
        return ApiResponse.success(HttpStatus.OK, "최신 신호 조회에 성공했습니다.", service.latest(symbol));
    }

    // 신호 전환 이력을 기간별로 조회할 때 사용
    @Override
    @GetMapping("/{symbol}/history")
    public ResponseEntity<ApiResponse<SignalHistoryResponse>> history(
            @PathVariable String symbol,
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(ApiResponse.success(HttpStatus.OK, "신호 이력 조회에 성공했습니다.",
                service.history(symbol, from, to, page, size)));
    }
}
