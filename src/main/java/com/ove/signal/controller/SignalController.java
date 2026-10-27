package com.ove.signal.controller;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import com.ove.global.response.ApiResponse;
import com.ove.signal.dto.LatestSignalResponse;
import com.ove.signal.service.SignalService;
import io.swagger.v3.oas.annotations.Operation;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/signals")
@RequiredArgsConstructor
public class SignalController {
    private final SignalService service;
    @Operation(summary = "최신 AI 신호 조회", description = "인증 필요. 생성 후 2분 이상 지난 신호는 stale=true. 미생성 시 404 SIGNAL_NOT_FOUND.")
    @GetMapping("/{symbol}")
    public ApiResponse<LatestSignalResponse> latest(@PathVariable String symbol) {
        return ApiResponse.success(HttpStatus.OK, "최신 신호 조회에 성공했습니다.", service.latest(symbol));
    }
}
