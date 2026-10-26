package com.ove.watchlist.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import com.ove.global.response.ApiResponse;
import com.ove.global.security.AuthUser;
import com.ove.watchlist.dto.WatchlistRequest;
import com.ove.watchlist.dto.WatchlistResponse;
import com.ove.watchlist.service.WatchlistService;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/watchlist")
@RequiredArgsConstructor
public class WatchlistController {
    private final WatchlistService watchlistService;

    @Operation(summary = "내 관심 종목 조회")
    @GetMapping
    public ResponseEntity<ApiResponse<WatchlistResponse>> get(@AuthenticationPrincipal AuthUser user) {
        return ResponseEntity.ok(ApiResponse.success(HttpStatus.OK, "요청에 성공했습니다.",
                watchlistService.getWatchlist(user.userId())));
    }

    @Operation(summary = "관심 종목 등록", description = "symbol을 전달합니다. 최대 20개. 중복 등록·개수 초과는 409입니다.")
    @PostMapping
    public ResponseEntity<ApiResponse<WatchlistResponse.Added>> add(@AuthenticationPrincipal AuthUser user,
            @Valid @RequestBody WatchlistRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(HttpStatus.CREATED,
                "관심 종목에 추가되었습니다.", watchlistService.add(user.userId(), request.symbol())));
    }

    @Operation(summary = "관심 종목 삭제", description = "본인의 관심 종목만 삭제합니다. 없는 항목은 404입니다.")
    @DeleteMapping("/{symbol}")
    public ResponseEntity<ApiResponse<Void>> remove(@AuthenticationPrincipal AuthUser user, @PathVariable String symbol) {
        watchlistService.remove(user.userId(), symbol);
        return ResponseEntity.ok(ApiResponse.success(HttpStatus.OK, "관심 종목에서 삭제되었습니다.", null));
    }
}
