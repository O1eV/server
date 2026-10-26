package com.ove.watchlist.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.ove.global.response.ApiResponse;
import com.ove.global.security.AuthUser;
import com.ove.watchlist.dto.WatchlistAddRequest;
import com.ove.watchlist.dto.WatchlistAddResponse;
import com.ove.watchlist.dto.WatchlistListResponse;
import com.ove.watchlist.service.WatchlistService;

import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/watchlist")
@RequiredArgsConstructor
public class WatchlistController {

    private final WatchlistService watchlistService;

    // 관심 종목에 종목을 추가할 때 사용
    @Operation(summary = "관심 종목 등록", description = "symbol을 전달합니다. 기본 최대 20개. 중복 등록·개수 초과는 409입니다.")
    @PostMapping
    public ResponseEntity<ApiResponse<WatchlistAddResponse>> add(
            @AuthenticationPrincipal AuthUser authUser,
            @Valid @RequestBody WatchlistAddRequest request) {
        WatchlistAddResponse response = watchlistService.add(authUser.userId(), request.getSymbol());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(HttpStatus.CREATED, "관심 종목에 추가되었습니다.", response));
    }

    // 내 관심 종목을 현재가와 함께 조회할 때 사용
    @Operation(summary = "내 관심 종목 조회")
    @GetMapping
    public ResponseEntity<ApiResponse<WatchlistListResponse>> getWatchlist(
            @AuthenticationPrincipal AuthUser authUser) {
        WatchlistListResponse response = watchlistService.getWatchlist(authUser.userId());
        return ResponseEntity.ok(ApiResponse.success(HttpStatus.OK, "요청에 성공했습니다.", response));
    }

    // 관심 종목에서 종목을 삭제할 때 사용
    @Operation(summary = "관심 종목 삭제", description = "본인의 관심 종목만 삭제합니다. 없는 항목은 404입니다.")
    @DeleteMapping("/{symbol}")
    public ResponseEntity<ApiResponse<Void>> remove(
            @AuthenticationPrincipal AuthUser authUser,
            @PathVariable String symbol) {
        watchlistService.remove(authUser.userId(), symbol);
        return ResponseEntity.ok(ApiResponse.success(HttpStatus.OK, "관심 종목에서 삭제되었습니다.", null));
    }
}
