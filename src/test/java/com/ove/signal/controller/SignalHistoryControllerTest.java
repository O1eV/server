package com.ove.signal.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import com.ove.auth.repository.TokenRedisRepository;
import com.ove.global.security.JwtProvider;
import com.ove.signal.domain.Signal;
import com.ove.signal.repository.SignalRepository;

/** GET /api/signals/{symbol}/history 통합 테스트(H2). */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class SignalHistoryControllerTest {

    private static final String URL = "/api/signals/KRW-BTC/history";

    @Autowired
    MockMvc mvc;
    @Autowired
    JwtProvider jwt;
    @Autowired
    SignalRepository repository;
    @MockitoBean
    TokenRedisRepository tokens;

    private String token() {
        return "Bearer " + jwt.createAccessToken(UUID.randomUUID()).token();
    }

    private void save(String previous, String value, Instant at) {
        repository.saveAndFlush(new Signal("KRW-BTC", previous, value, new BigDecimal("0.71"), new BigDecimal("0.21"),
                new BigDecimal("0.08"), new BigDecimal("0.71"), new BigDecimal("98500000"), at, "model-v1"));
    }

    @Test
    @DisplayName("로그인이 필요하다")
    void requiresLogin() throws Exception {
        mvc.perform(get(URL)).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("지원하지 않는 종목은 404 MARKET_NOT_FOUND")
    void unsupportedSymbol() throws Exception {
        mvc.perform(get("/api/signals/UNKNOWN/history").header("Authorization", token()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("MARKET_NOT_FOUND"));
    }

    @Test
    @DisplayName("90일 초과·from>to·잘못된 page/size·잘못된 시각은 400 INVALID_REQUEST")
    void invalidRequests() throws Exception {
        String[] queries = {
                "from=2026-01-01T00:00:00Z&to=2026-10-01T00:00:00Z",
                "from=2026-10-02T00:00:00Z&to=2026-10-01T00:00:00Z",
                "page=-1", "size=0", "size=101", "from=yesterday"};
        for (String query : queries) {
            mvc.perform(get(URL + "?" + query).header("Authorization", token()))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("INVALID_REQUEST"));
        }
    }

    @Test
    @DisplayName("200: 기본 최근 7일의 전환 행만 최신순으로, envelope과 최신 신호 필드 형식으로 돌려준다")
    void returnsTransitionsInEnvelope() throws Exception {
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        save("BUY", "SELL", now.minus(10, ChronoUnit.DAYS)); // 7일 밖
        save("HOLD", "BUY", now.minus(2, ChronoUnit.DAYS));
        save(null, "BUY", now.minus(1, ChronoUnit.DAYS));    // 전환 아님
        save("BUY", "SELL", now.minus(1, ChronoUnit.HOURS));

        mvc.perform(get("/api/signals/krw-btc/history").header("Authorization", token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.statusCode").value(200))
                .andExpect(jsonPath("$.path").value("/api/signals/krw-btc/history"))
                .andExpect(jsonPath("$.error").isEmpty())
                .andExpect(jsonPath("$.data.items.length()").value(2))
                .andExpect(jsonPath("$.data.items[0].signal").value("SELL"))
                .andExpect(jsonPath("$.data.items[0].previousSignal").value("BUY"))
                .andExpect(jsonPath("$.data.items[0].symbol").value("KRW-BTC"))
                .andExpect(jsonPath("$.data.items[0].buyProbability").value(.71))
                .andExpect(jsonPath("$.data.items[0].confidence").value(.71))
                .andExpect(jsonPath("$.data.items[0].modelVersion").value("model-v1"))
                .andExpect(jsonPath("$.data.items[1].signal").value("BUY"))
                .andExpect(jsonPath("$.data.page").value(0))
                .andExpect(jsonPath("$.data.size").value(20))
                .andExpect(jsonPath("$.data.totalElements").value(2))
                .andExpect(jsonPath("$.data.totalPages").value(1));
    }

    @Test
    @DisplayName("이력이 없으면 빈 목록 200")
    void emptyHistory() throws Exception {
        mvc.perform(get(URL).header("Authorization", token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(0))
                .andExpect(jsonPath("$.data.totalElements").value(0));
    }
}
