package com.ove.market.controller;

import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.ove.auth.repository.TokenRedisRepository;
import com.ove.global.exception.BusinessException;
import com.ove.global.exception.ErrorCode;
import com.ove.global.security.JwtProvider;
import com.ove.market.collector.MarketCollector;
import com.ove.market.dto.CandleListResponse;
import com.ove.market.dto.CollectedPriceResponse;
import com.ove.market.service.DemoStockService;

/** 키움 수집기가 켜졌을 때(서비스는 가짜)만 열리는 /api/demo/stocks 엔드포인트. */
@SpringBootTest(properties = {"collector.kiwoom.enabled=true", "collector.kiwoom.app-key=test-not-a-real-key",
        "collector.kiwoom.app-secret=test-not-a-real-secret", "collector.kiwoom.web-socket-url=ws://localhost:1/none",
        "collector.kiwoom.rest-base-url=http://localhost:1"})
@AutoConfigureMockMvc
class DemoStockControllerTest {

    @Autowired
    MockMvc mvc;
    @Autowired
    JwtProvider jwt;
    @MockitoBean
    TokenRedisRepository tokens;
    @MockitoBean
    DemoStockService service;
    // 실제 수집기는 기동 때 TimescaleDB·키움에 연결하므로 가짜로 바꾼다.
    @MockitoBean
    MarketCollector collector;

    private String token() {
        return "Bearer " + jwt.createAccessToken(UUID.randomUUID()).token();
    }

    @Test
    @DisplayName("로그인이 필요하다")
    void requiresLogin() throws Exception {
        mvc.perform(get("/api/demo/stocks/005930/price")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/demo/stocks/005930/candles")).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("현재가: 200 envelope, no-store")
    void price() throws Exception {
        Instant now = Instant.parse("2026-10-26T00:00:00Z");
        given(service.getPrice("005930")).willReturn(new CollectedPriceResponse("005930", new BigDecimal("71200"), now, now, false));

        mvc.perform(get("/api/demo/stocks/005930/price").header("Authorization", token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.symbol").value("005930"))
                .andExpect(jsonPath("$.data.price").value(71200))
                .andExpect(jsonPath("$.data.stale").value(false))
                .andExpect(header().string("Cache-Control", "no-store"));
    }

    @Test
    @DisplayName("미지원 종목 404 MARKET_NOT_FOUND, 미수신 503 COLLECTOR_UNAVAILABLE")
    void errors() throws Exception {
        given(service.getPrice("000660")).willThrow(new BusinessException(ErrorCode.MARKET_NOT_FOUND));
        given(service.getPrice("005930")).willThrow(new BusinessException(ErrorCode.COLLECTOR_UNAVAILABLE));

        mvc.perform(get("/api/demo/stocks/000660/price").header("Authorization", token()))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.error").value("MARKET_NOT_FOUND"));
        mvc.perform(get("/api/demo/stocks/005930/price").header("Authorization", token()))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.error").value("COLLECTOR_UNAVAILABLE"));
    }

    @Test
    @DisplayName("캔들: 기본값(1m, 200)으로 서비스를 부르고 envelope으로 돌려준다")
    void candles() throws Exception {
        given(service.getCandles("005930", "1m", null, null, 200))
                .willReturn(new CandleListResponse("005930", "1m", List.of()));

        mvc.perform(get("/api/demo/stocks/005930/candles").header("Authorization", token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.symbol").value("005930"))
                .andExpect(jsonPath("$.data.interval").value("1m"))
                .andExpect(jsonPath("$.data.candles.length()").value(0));
    }
}
