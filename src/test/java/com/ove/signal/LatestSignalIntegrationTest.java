package com.ove.signal;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
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

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class LatestSignalIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired JwtProvider jwt;
    @Autowired SignalRepository repository;
    @MockitoBean TokenRedisRepository tokens;
    @Autowired com.ove.realtime.service.RealtimePublisher publisher;

    private String token() { return "Bearer " + jwt.createAccessToken(UUID.randomUUID()).token(); }
    private Signal signal(Instant time, String value) {
        return new Signal("KRW-BTC", "HOLD", value, new BigDecimal("0.71"), new BigDecimal("0.21"),
            new BigDecimal("0.08"), new BigDecimal("0.71"), new BigDecimal("98500000"), time, "model-v1");
    }
    @Test void 로그인_필수() throws Exception {
        mvc.perform(get("/api/signals/KRW-BTC")).andExpect(status().isUnauthorized());
    }
    @Test void 미생성_신호_404() throws Exception {
        mvc.perform(get("/api/signals/KRW-BTC").header("Authorization", token()))
            .andExpect(status().isNotFound()).andExpect(jsonPath("$.error").value("SIGNAL_NOT_FOUND"));
    }
    @Test void 최신_신호의_확률과_메타데이터를_조회한다() throws Exception {
        repository.saveAndFlush(signal(Instant.now().minusSeconds(300), "HOLD"));
        repository.saveAndFlush(signal(Instant.now().minusSeconds(10), "BUY"));
        mvc.perform(get("/api/signals/krw-btc").header("Authorization", token()))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.signal").value("BUY"))
            .andExpect(jsonPath("$.data.buyProbability").value(.71))
            .andExpect(jsonPath("$.data.holdProbability").value(.21))
            .andExpect(jsonPath("$.data.sellProbability").value(.08))
            .andExpect(jsonPath("$.data.confidence").value(.71))
            .andExpect(jsonPath("$.data.modelVersion").value("model-v1"))
            .andExpect(jsonPath("$.data.stale").value(false));
    }
    @Test void 두분_지난_신호는_지연됨() throws Exception {
        repository.saveAndFlush(signal(Instant.now().minusSeconds(121), "BUY"));
        mvc.perform(get("/api/signals/KRW-BTC").header("Authorization", token()))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.stale").value(true));
    }
    @Test void 실제_확률이_포함된_신호_발행은_최신조회에도_저장된다() throws Exception {
        publisher.publishSignalChange("KRW-BTC", "HOLD", "BUY", java.util.Map.of(
            "buy", new BigDecimal("0.71"), "hold", new BigDecimal("0.21"), "sell", new BigDecimal("0.08")),
            new BigDecimal("0.71"), false);
        mvc.perform(get("/api/signals/KRW-BTC").header("Authorization", token()))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.signal").value("BUY"));
    }

    @Test void 미지원_종목은_404() throws Exception {
        mvc.perform(get("/api/signals/UNKNOWN").header("Authorization", token()))
            .andExpect(status().isNotFound()).andExpect(jsonPath("$.error").value("MARKET_NOT_FOUND"));
    }
}
