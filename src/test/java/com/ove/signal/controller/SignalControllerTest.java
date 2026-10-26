package com.ove.signal.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.ove.auth.repository.TokenRedisRepository;
import com.ove.global.security.JwtProvider;
import com.ove.signal.domain.Signal;
import com.ove.signal.domain.SignalType;
import com.ove.signal.repository.SignalRepository;

/**
 * 신호 조회 API의 인증·응답·에러 형식을 확인한다.
 * Redis는 JWT 필터가 참조하는 토큰 저장소만 목으로 바꿔 Docker 없이 돌린다.
 */
@SpringBootTest
@AutoConfigureMockMvc
class SignalControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private SignalRepository signalRepository;

    @Autowired
    private JwtProvider jwtProvider;

    @MockitoBean
    private TokenRedisRepository tokenRedisRepository;

    private String accessToken;

    @BeforeEach
    void setUp() {
        accessToken = jwtProvider.createAccessToken(UUID.randomUUID()).token();
        signalRepository.save(signal(null, SignalType.HOLD));
        signalRepository.save(signal(SignalType.HOLD, SignalType.BUY));
        signalRepository.save(signal(null, SignalType.BUY));
    }

    @AfterEach
    void tearDown() {
        signalRepository.deleteAllInBatch();
    }

    @Test
    @DisplayName("최신 신호와 전환 이력만 조회한다")
    void latestAndHistory() throws Exception {
        mockMvc.perform(get("/api/signals/krw-btc").header("Authorization", bearer(accessToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.statusCode").value(200))
                .andExpect(jsonPath("$.data.symbol").value("KRW-BTC"))
                .andExpect(jsonPath("$.data.signal").value("BUY"))
                .andExpect(jsonPath("$.data.previousSignal").isEmpty())
                .andExpect(jsonPath("$.data.buyProbability").value(0.7))
                .andExpect(jsonPath("$.data.confidence").value(0.8))
                .andExpect(jsonPath("$.data.stale").isBoolean());

        mockMvc.perform(get("/api/signals/KRW-BTC/history")
                        .header("Authorization", bearer(accessToken))
                        .param("page", "0")
                        .param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].previousSignal").value("HOLD"))
                .andExpect(jsonPath("$.data.items[0].signal").value("BUY"))
                .andExpect(jsonPath("$.data.totalElements").value(1))
                .andExpect(jsonPath("$.data.totalPages").value(1));
    }

    @Test
    @DisplayName("토큰이 없으면 401, 없는 종목·신호는 404, 잘못된 페이지는 400")
    void errors() throws Exception {
        mockMvc.perform(get("/api/signals/KRW-BTC"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("UNAUTHORIZED"));

        mockMvc.perform(get("/api/signals/KRW-ETH").header("Authorization", bearer(accessToken)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("SIGNAL_NOT_FOUND"));

        mockMvc.perform(get("/api/signals/BTC").header("Authorization", bearer(accessToken)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("MARKET_NOT_FOUND"));

        mockMvc.perform(get("/api/signals/KRW-BTC/history")
                        .header("Authorization", bearer(accessToken))
                        .param("page", "-1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_REQUEST"));

        mockMvc.perform(get("/api/signals/KRW-BTC/history")
                        .header("Authorization", bearer(accessToken))
                        .param("from", "2026-10-27T00:00:00Z")
                        .param("to", "2026-10-26T00:00:00Z"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_REQUEST"));
    }

    private Signal signal(SignalType previousSignal, SignalType signal) {
        return Signal.builder()
                .symbol("KRW-BTC")
                .previousSignal(previousSignal)
                .signal(signal)
                .buyProbability(new BigDecimal("0.7"))
                .holdProbability(new BigDecimal("0.2"))
                .sellProbability(new BigDecimal("0.1"))
                .confidence(new BigDecimal("0.8"))
                .price(new BigDecimal("1000"))
                .build();
    }

    private String bearer(String token) {
        return "Bearer " + token;
    }
}
