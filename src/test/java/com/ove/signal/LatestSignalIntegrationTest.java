package com.ove.signal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
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
import com.ove.signal.dto.SignalSummary;
import com.ove.signal.repository.SignalRepository;
import com.ove.signal.service.SignalService;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class LatestSignalIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired JwtProvider jwt;
    @Autowired SignalRepository repository;
    @Autowired SignalService signalService;
    @MockitoBean TokenRedisRepository tokens;
    @Autowired com.ove.realtime.service.RealtimePublisher publisher;

    private String token() { return "Bearer " + jwt.createAccessToken(UUID.randomUUID()).token(); }
    private Signal signal(Instant time, String value) {
        return signal("KRW-BTC", time, value);
    }
    private Signal signal(String symbol, Instant time, String value) {
        return new Signal(symbol, "HOLD", value, new BigDecimal("0.71"), new BigDecimal("0.21"),
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

    @Test void 여러_종목의_최신_신호를_한_번에_조회한다() {
        repository.saveAndFlush(signal("KRW-BTC", Instant.now().minusSeconds(60), "HOLD"));
        repository.saveAndFlush(signal("KRW-BTC", Instant.now(), "BUY"));
        repository.saveAndFlush(signal("KRW-ETH", Instant.now(), "SELL"));

        Map<String, SignalSummary> latest = signalService.latestSummaries(
            List.of("KRW-BTC", "KRW-ETH", "KRW-XRP"));

        assertThat(latest).containsOnlyKeys("KRW-BTC", "KRW-ETH");
        assertThat(latest.get("KRW-BTC").signal()).isEqualTo("BUY");
        assertThat(latest.get("KRW-BTC").confidence()).isEqualByComparingTo("0.71");
        assertThat(latest.get("KRW-ETH").signal()).isEqualTo("SELL");
    }

    @Test void 같은_시각이면_나중에_저장된_신호를_최신으로_본다() {
        Instant time = Instant.now();
        repository.saveAndFlush(signal("KRW-BTC", time, "HOLD"));
        repository.saveAndFlush(signal("KRW-BTC", time, "SELL"));

        Map<String, SignalSummary> latest = signalService.latestSummaries(List.of("KRW-BTC"));

        assertThat(latest.get("KRW-BTC").signal()).isEqualTo("SELL");
    }
}
