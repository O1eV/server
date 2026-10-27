package com.ove.market.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.ove.auth.repository.TokenRedisRepository;
import com.ove.global.security.JwtProvider;
import com.ove.market.service.DemoStockService;

/** 기본값(키움 수집기 꺼짐)에서는 주식 시연 API가 없다(404). */
@SpringBootTest
@AutoConfigureMockMvc
class DemoStockDisabledTest {

    @Autowired
    MockMvc mvc;
    @Autowired
    JwtProvider jwt;
    @Autowired
    ApplicationContext context;
    @MockitoBean
    TokenRedisRepository tokens;

    @Test
    @DisplayName("비활성이면 빈이 없고 price·candles 모두 404")
    void disabledReturns404() throws Exception {
        String token = "Bearer " + jwt.createAccessToken(UUID.randomUUID()).token();

        assertThat(context.getBeansOfType(DemoStockService.class)).isEmpty();
        assertThat(context.getBeansOfType(DemoStockController.class)).isEmpty();
        mvc.perform(get("/api/demo/stocks/005930/price").header("Authorization", token)).andExpect(status().isNotFound());
        mvc.perform(get("/api/demo/stocks/005930/candles").header("Authorization", token)).andExpect(status().isNotFound());
    }
}
