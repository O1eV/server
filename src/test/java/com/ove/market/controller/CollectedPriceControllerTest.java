package com.ove.market.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.mockito.BDDMockito.given;
import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import com.ove.auth.repository.TokenRedisRepository;
import com.ove.market.service.CollectedPriceService;
import com.ove.market.dto.CollectedPriceResponse;
import com.ove.global.exception.BusinessException;
import com.ove.global.exception.ErrorCode;

@SpringBootTest
@AutoConfigureMockMvc
class CollectedPriceControllerTest {
    @Autowired MockMvc mvc;
    @MockitoBean TokenRedisRepository tokens;
    @MockitoBean CollectedPriceService service;

    @Test void 비로그인도_수집시세를_조회한다() throws Exception {
        Instant now = Instant.parse("2026-10-26T00:00:00Z");
        given(service.getPrice("KRW-BTC")).willReturn(new CollectedPriceResponse("KRW-BTC", new BigDecimal("98500000"), now, now, false));
        mvc.perform(get("/api/markets/KRW-BTC/price"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.price").value(98500000))
            .andExpect(jsonPath("$.data.stale").value(false))
            .andExpect(header().string("Cache-Control", "no-store"));
    }
    @Test void 수집_미수신은_503_envelope() throws Exception {
        given(service.getPrice("KRW-BTC")).willThrow(new BusinessException(ErrorCode.COLLECTOR_UNAVAILABLE));
        mvc.perform(get("/api/markets/KRW-BTC/price"))
            .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.error").value("COLLECTOR_UNAVAILABLE"));
    }
}
