package com.ove.paper.controller;

import org.springframework.web.bind.annotation.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.http.HttpStatus;
import com.ove.global.security.AuthUser;
import com.ove.global.response.ApiResponse;
import com.ove.paper.dto.OrderHistoryResponse;
import com.ove.paper.service.PaperOrderHistoryService;
import lombok.RequiredArgsConstructor;
import io.swagger.v3.oas.annotations.Operation;

@RestController
@RequiredArgsConstructor
public class PaperOrderHistoryController {
    private final PaperOrderHistoryService service;
    @Operation(summary = "내 모의 거래 내역 조회", description = "종목 필터, 최신순 페이지 조회. page>=0, size=1~100. 당시 신호·수수료가 저장되지 않은 과거 이력은 null.")
    @GetMapping("/api/paper/orders")
    public ApiResponse<OrderHistoryResponse> history(@AuthenticationPrincipal AuthUser user,
        @RequestParam(required = false) String symbol, @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.success(HttpStatus.OK, "요청에 성공했습니다.", service.getOrders(user.userId(), symbol, page, size));
    }
}
