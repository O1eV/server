package com.ove.paper.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.ove.global.response.ApiResponse;
import com.ove.global.security.AuthUser;
import com.ove.paper.dto.PaperOrderHistoryResponse;
import com.ove.paper.dto.PaperOrderRequest;
import com.ove.paper.dto.PaperOrderResponse;
import com.ove.paper.dto.PaperPortfolioResponse;
import com.ove.paper.service.PaperTradingService;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;

@Validated
@RestController
@RequestMapping("/api/paper")
@RequiredArgsConstructor
public class PaperTradingController {

    private final PaperTradingService paperTradingService;

    @PostMapping("/reset")
    public ResponseEntity<ApiResponse<PaperPortfolioResponse>> reset(@AuthenticationPrincipal AuthUser authUser) {
        return ResponseEntity.ok(ApiResponse.success(HttpStatus.OK, "모의 계좌를 초기화했습니다.",
                paperTradingService.reset(authUser.userId())));
    }

    @GetMapping("/portfolio")
    public ResponseEntity<ApiResponse<PaperPortfolioResponse>> getPortfolio(
            @AuthenticationPrincipal AuthUser authUser) {
        return ResponseEntity.ok(ApiResponse.success(HttpStatus.OK, "모의 포트폴리오 조회에 성공했습니다.",
                paperTradingService.getPortfolio(authUser.userId())));
    }

    @GetMapping("/orders")
    public ResponseEntity<ApiResponse<PaperOrderHistoryResponse>> getOrders(
            @AuthenticationPrincipal AuthUser authUser,
            @RequestParam(required = false) String symbol,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return ResponseEntity.ok(ApiResponse.success(HttpStatus.OK, "모의 거래 내역 조회에 성공했습니다.",
                paperTradingService.getOrders(authUser.userId(), symbol, page, size)));
    }

    @PostMapping("/orders")
    public ResponseEntity<ApiResponse<PaperOrderResponse>> placeOrder(
            @AuthenticationPrincipal AuthUser authUser, @Valid @RequestBody PaperOrderRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(HttpStatus.CREATED, "모의 주문이 체결되었습니다.",
                        paperTradingService.placeOrder(authUser.userId(), request)));
    }
}
