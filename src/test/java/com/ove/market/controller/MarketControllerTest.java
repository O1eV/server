package com.ove.market.controller;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.client.ResourceAccessException;

import com.ove.market.client.UpbitCandleResponse;
import com.ove.market.client.UpbitClient;
import com.ove.market.client.UpbitMarketResponse;
import com.ove.market.client.UpbitTickerResponse;

@SpringBootTest
@AutoConfigureMockMvc
class MarketControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private UpbitClient upbitClient;

    // 테스트마다 가짜 업비트 종목·현재가를 준비할 때 사용
    @BeforeEach
    void setUp() {
        given(upbitClient.getMarkets()).willReturn(List.of(
                market("KRW-BTC", "비트코인", "Bitcoin", null),
                market("KRW-ETH", "이더리움", "Ethereum",
                        new UpbitMarketResponse.MarketEvent(false, Map.of("PRICE_FLUCTUATIONS", true))),
                market("BTC-XRP", "엑스알피", "XRP", null)));

        given(upbitClient.getTickers()).willReturn(List.of(
                ticker("KRW-BTC", "RISE"),
                ticker("KRW-ETH", "FALL")));
    }

    @Test
    @DisplayName("종목 목록: 로그인 없이 원화 종목만 나온다")
    void getMarkets() throws Exception {
        mockMvc.perform(get("/api/markets"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.statusCode").value(200))
                .andExpect(jsonPath("$.path").value("/api/markets"))
                .andExpect(jsonPath("$.message").value("종목 목록 조회 성공"))
                .andExpect(jsonPath("$.error").value(nullValue()))
                .andExpect(jsonPath("$.data", hasSize(2)))
                .andExpect(jsonPath("$.data[0].symbol").value("KRW-BTC"))
                .andExpect(jsonPath("$.data[0].marketState").value("NORMAL"))
                .andExpect(jsonPath("$.data[0].sign").value("RISE"))
                .andExpect(jsonPath("$.data[1].symbol").value("KRW-ETH"))
                .andExpect(jsonPath("$.data[1].marketState").value("CAUTION"));
    }

    @Test
    @DisplayName("종목 목록: 필터를 넣으면 맞는 종목만 나온다")
    void getMarketsWithFilter() throws Exception {
        mockMvc.perform(get("/api/markets").param("sign", "RISE"))
                .andExpect(jsonPath("$.data", hasSize(1)))
                .andExpect(jsonPath("$.data[0].symbol").value("KRW-BTC"));

        mockMvc.perform(get("/api/markets").param("marketState", "CAUTION"))
                .andExpect(jsonPath("$.data", hasSize(1)))
                .andExpect(jsonPath("$.data[0].symbol").value("KRW-ETH"));

        mockMvc.perform(get("/api/markets").param("assetClass", "STOCK"))
                .andExpect(jsonPath("$.data", hasSize(0)));
    }

    @Test
    @DisplayName("종목 검색: 대소문자 상관없이 코드·이름으로 찾는다")
    void searchMarkets() throws Exception {
        mockMvc.perform(get("/api/markets/search").param("q", "btc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(1)))
                .andExpect(jsonPath("$.data[0].symbol").value("KRW-BTC"));

        mockMvc.perform(get("/api/markets/search").param("q", "이더"))
                .andExpect(jsonPath("$.data[0].symbol").value("KRW-ETH"));
    }

    @Test
    @DisplayName("종목 검색: 검색어가 없으면 400 에러")
    void searchMarketsWithoutQuery() throws Exception {
        mockMvc.perform(get("/api/markets/search"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.data").value(nullValue()));
    }

    @Test
    @DisplayName("캔들 조회: 과거 → 최신 순서로 나오고, from보다 오래된 캔들은 빠진다")
    void getCandles() throws Exception {
        given(upbitClient.getCandles(eq("minutes/60"), eq("KRW-BTC"), any(), eq(200))).willReturn(List.of(
                candle("2026-10-19T12:00:00", 3),
                candle("2026-10-19T11:00:00", 2),
                candle("2026-10-19T10:00:00", 1)));

        mockMvc.perform(get("/api/markets/KRW-BTC/candles")
                        .param("interval", "1h")
                        .param("from", "2026-10-19T11:00:00Z"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(2)))
                .andExpect(jsonPath("$.data[0].time").value("2026-10-19T11:00:00Z"))
                .andExpect(jsonPath("$.data[0].close").value(2))
                .andExpect(jsonPath("$.data[1].time").value("2026-10-19T12:00:00Z"))
                .andExpect(jsonPath("$.data[1].close").value(3));
    }

    @Test
    @DisplayName("캔들 조회: 잘못된 interval이면 400, 없는 종목이면 404")
    void getCandlesError() throws Exception {
        mockMvc.perform(get("/api/markets/KRW-BTC/candles").param("interval", "2m"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_REQUEST"));

        mockMvc.perform(get("/api/markets/KRW-BTC/candles").param("from", "어제"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_REQUEST"));

        mockMvc.perform(get("/api/markets/KRW-ABC/candles"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("MARKET_NOT_FOUND"))
                .andExpect(jsonPath("$.path").value("/api/markets/KRW-ABC/candles"));
    }

    @Test
    @DisplayName("업비트가 응답하지 않으면 503 에러")
    void upbitDown() throws Exception {
        given(upbitClient.getCandles(any(), any(), any(), anyInt()))
                .willThrow(new ResourceAccessException("업비트 연결 실패"));

        mockMvc.perform(get("/api/markets/KRW-BTC/candles"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error").value("MARKET_DATA_UNAVAILABLE"));
    }

    // 테스트용 가짜 업비트 종목을 만들 때 사용
    private UpbitMarketResponse market(String code, String koreanName, String englishName,
                                       UpbitMarketResponse.MarketEvent event) {
        return UpbitMarketResponse.builder()
                .market(code)
                .koreanName(koreanName)
                .englishName(englishName)
                .marketEvent(event)
                .build();
    }

    // 테스트용 가짜 업비트 현재가를 만들 때 사용
    private UpbitTickerResponse ticker(String code, String change) {
        return UpbitTickerResponse.builder()
                .market(code)
                .tradePrice(new BigDecimal("1000"))
                .change(change)
                .signedChangePrice(new BigDecimal("10"))
                .signedChangeRate(new BigDecimal("0.01"))
                .accTradePrice24h(new BigDecimal("300"))
                .build();
    }

    // 테스트용 가짜 업비트 캔들을 만들 때 사용
    private UpbitCandleResponse candle(String timeUtc, int price) {
        return UpbitCandleResponse.builder()
                .candleDateTimeUtc(timeUtc)
                .openingPrice(new BigDecimal(price))
                .highPrice(new BigDecimal(price))
                .lowPrice(new BigDecimal(price))
                .tradePrice(new BigDecimal(price))
                .candleAccTradeVolume(new BigDecimal("0.5"))
                .build();
    }
}
