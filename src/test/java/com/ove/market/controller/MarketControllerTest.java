package com.ove.market.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.notNull;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

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
import com.ove.market.client.UpbitCandleResponse;
import com.ove.market.client.UpbitClient;
import com.ove.market.client.UpbitMarketResponse;
import com.ove.market.client.UpbitTickerResponse;

@SpringBootTest
@AutoConfigureMockMvc
class MarketControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtProvider jwtProvider;

    @MockitoBean
    private UpbitClient upbitClient;

    @MockitoBean
    private TokenRedisRepository tokenRedisRepository;

    // 테스트마다 가짜 업비트 종목·현재가를 준비할 때 사용
    @BeforeEach
    void setUp() {
        given(upbitClient.getMarkets()).willReturn(List.of(
                market("KRW-BTC", "비트코인", "Bitcoin"),
                market("KRW-ETH", "이더리움", "Ethereum"),
                market("KRW-XRP", "엑스알피", "XRP"),
                market("KRW-DOGE", "도지코인", "Dogecoin"),
                market("BTC-XRP", "엑스알피", "XRP")));

        given(upbitClient.getTickers(anyList())).willReturn(List.of(
                ticker("KRW-BTC", "0.02"),
                ticker("KRW-ETH", "-0.02"),
                ticker("KRW-XRP", "0.005")));
    }

    @Test
    @DisplayName("종목 목록: 로그인 없이 MVP 3종목이 페이지 형태로 나온다")
    void getMarkets() throws Exception {
        mockMvc.perform(get("/api/markets"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.statusCode").value(200))
                .andExpect(jsonPath("$.path").value("/api/markets"))
                .andExpect(jsonPath("$.message").value("요청에 성공했습니다."))
                .andExpect(jsonPath("$.error").value(nullValue()))
                .andExpect(jsonPath("$.data.items", hasSize(3)))
                .andExpect(jsonPath("$.data.items[0].symbol").value("KRW-BTC"))
                .andExpect(jsonPath("$.data.items[0].koreanName").value("비트코인"))
                .andExpect(jsonPath("$.data.items[0].assetClass").value("COIN"))
                .andExpect(jsonPath("$.data.items[0].marketState").value("UP"))
                .andExpect(jsonPath("$.data.items[0].price").value(1000))
                .andExpect(jsonPath("$.data.items[0].changeRate").value(0.02))
                .andExpect(jsonPath("$.data.items[0].signal").value(nullValue()))
                .andExpect(jsonPath("$.data.items[0].confidence").value(nullValue()))
                .andExpect(jsonPath("$.data.page").value(0))
                .andExpect(jsonPath("$.data.size").value(20))
                .andExpect(jsonPath("$.data.totalElements").value(3));
    }

    @Test
    @DisplayName("종목 목록: 자산군·시장 상태 필터와 페이지가 적용된다")
    void getMarketsWithFilter() throws Exception {
        mockMvc.perform(get("/api/markets").param("marketState", "DOWN"))
                .andExpect(jsonPath("$.data.items", hasSize(1)))
                .andExpect(jsonPath("$.data.items[0].symbol").value("KRW-ETH"));

        mockMvc.perform(get("/api/markets").param("assetClass", "KR_STOCK"))
                .andExpect(jsonPath("$.data.items", hasSize(0)))
                .andExpect(jsonPath("$.data.totalElements").value(0));

        mockMvc.perform(get("/api/markets").param("page", "1").param("size", "2"))
                .andExpect(jsonPath("$.data.items", hasSize(1)))
                .andExpect(jsonPath("$.data.items[0].symbol").value("KRW-XRP"))
                .andExpect(jsonPath("$.data.totalElements").value(3));
    }

    @Test
    @DisplayName("종목 목록: 정해진 값이 아닌 필터는 400 에러")
    void getMarketsWithInvalidFilter() throws Exception {
        mockMvc.perform(get("/api/markets").param("marketState", "RISE"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.message").value("유효하지 않은 필터 값입니다."));
    }

    @Test
    @DisplayName("종목 목록: signal 필터는 로그인해야 쓸 수 있다")
    void getMarketsWithSignal() throws Exception {
        mockMvc.perform(get("/api/markets").param("signal", "BUY"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("UNAUTHORIZED"));

        String accessToken = jwtProvider.createAccessToken(UUID.randomUUID()).token();
        mockMvc.perform(get("/api/markets").param("signal", "BUY")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items", hasSize(0)));
    }

    @Test
    @DisplayName("시장 상태: 변동률 기준으로 UP / DOWN / SIDEWAYS / VOLATILE이 정해진다")
    void marketState() {
        assertThat(ticker("KRW-BTC", "0.02").getMarketState()).isEqualTo("UP");
        assertThat(ticker("KRW-BTC", "-0.02").getMarketState()).isEqualTo("DOWN");
        assertThat(ticker("KRW-BTC", "0.005").getMarketState()).isEqualTo("SIDEWAYS");
        assertThat(ticker("KRW-BTC", "-0.08").getMarketState()).isEqualTo("VOLATILE");
    }

    @Test
    @DisplayName("종목 검색: 한글 이름·영어 이름·심볼 부분 일치로 MVP 종목에서 찾는다")
    void searchMarkets() throws Exception {
        mockMvc.perform(get("/api/markets/search").param("q", "btc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items", hasSize(1)))
                .andExpect(jsonPath("$.data.items[0].symbol").value("KRW-BTC"))
                .andExpect(jsonPath("$.data.items[0].assetClass").value("COIN"));

        mockMvc.perform(get("/api/markets/search").param("q", "이더"))
                .andExpect(jsonPath("$.data.items[0].symbol").value("KRW-ETH"));

        mockMvc.perform(get("/api/markets/search").param("q", "도지"))
                .andExpect(jsonPath("$.data.items", hasSize(0)));
    }

    @Test
    @DisplayName("종목 검색: 검색어가 없으면 400 에러")
    void searchMarketsWithoutQuery() throws Exception {
        mockMvc.perform(get("/api/markets/search"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.message").value("검색어를 입력해 주세요."));
    }

    @Test
    @DisplayName("캔들 조회: 시간 오름차순으로 나오고, from보다 오래된 캔들은 빠진다")
    void getCandles() throws Exception {
        given(upbitClient.getCandles(eq("minutes/60"), eq("KRW-BTC"), any(), eq(200))).willReturn(List.of(
                candle("2026-10-19T12:00:00", 3),
                candle("2026-10-19T11:00:00", 2),
                candle("2026-10-19T10:00:00", 1)));

        mockMvc.perform(get("/api/markets/KRW-BTC/candles")
                        .param("interval", "1h")
                        .param("from", "2026-10-19T11:00:00Z"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.symbol").value("KRW-BTC"))
                .andExpect(jsonPath("$.data.interval").value("1h"))
                .andExpect(jsonPath("$.data.candles", hasSize(2)))
                .andExpect(jsonPath("$.data.candles[0].time").value("2026-10-19T11:00:00Z"))
                .andExpect(jsonPath("$.data.candles[0].close").value(2))
                .andExpect(jsonPath("$.data.candles[1].time").value("2026-10-19T12:00:00Z"))
                .andExpect(jsonPath("$.data.candles[1].close").value(3));
    }

    @Test
    @DisplayName("캔들 조회: 200개가 넘으면 업비트에서 나눠 받아 합친다")
    void getCandlesOver200() throws Exception {
        Instant latest = Instant.parse("2026-10-19T12:00:00Z");
        given(upbitClient.getCandles(eq("minutes/1"), eq("KRW-BTC"), isNull(), eq(200)))
                .willReturn(candles(latest, 200));
        given(upbitClient.getCandles(eq("minutes/1"), eq("KRW-BTC"), notNull(), eq(50)))
                .willReturn(candles(latest.minusSeconds(200 * 60), 50));

        mockMvc.perform(get("/api/markets/KRW-BTC/candles").param("limit", "250"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.candles", hasSize(250)))
                .andExpect(jsonPath("$.data.candles[0].time").value("2026-10-19T07:51:00Z"))
                .andExpect(jsonPath("$.data.candles[249].time").value("2026-10-19T12:00:00Z"));
    }

    @Test
    @DisplayName("캔들 조회: 잘못된 interval·limit은 400, 제공하지 않는 종목은 404")
    void getCandlesError() throws Exception {
        mockMvc.perform(get("/api/markets/KRW-BTC/candles").param("interval", "3m"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_REQUEST"));

        mockMvc.perform(get("/api/markets/KRW-BTC/candles").param("limit", "501"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_REQUEST"));

        mockMvc.perform(get("/api/markets/KRW-DOGE/candles"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("MARKET_NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("종목을 찾을 수 없습니다."))
                .andExpect(jsonPath("$.path").value("/api/markets/KRW-DOGE/candles"));
    }

    // 테스트용 가짜 업비트 종목을 만들 때 사용
    private UpbitMarketResponse market(String code, String koreanName, String englishName) {
        return UpbitMarketResponse.builder()
                .market(code)
                .koreanName(koreanName)
                .englishName(englishName)
                .build();
    }

    // 테스트용 가짜 업비트 현재가를 만들 때 사용
    private UpbitTickerResponse ticker(String code, String changeRate) {
        return UpbitTickerResponse.builder()
                .market(code)
                .tradePrice(new BigDecimal("1000"))
                .signedChangeRate(new BigDecimal(changeRate))
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

    // latest부터 1분씩 과거로 가는 가짜 캔들 묶음을 만들 때 사용 (업비트처럼 최신순)
    private List<UpbitCandleResponse> candles(Instant latest, int count) {
        List<UpbitCandleResponse> result = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            String time = latest.minusSeconds(i * 60L).toString();
            result.add(candle(time.substring(0, time.length() - 1), 1));
        }
        return result;
    }
}
