package com.ove.paper.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.ResultActions;

import com.ove.account.domain.PaperAccount;
import com.ove.global.exception.BusinessException;
import com.ove.market.service.MarketPriceProvider;
import com.ove.paper.domain.OrderSide;
import com.ove.paper.dto.PaperOrderRequest;
import com.ove.paper.service.PaperTradingService;
import com.ove.support.IntegrationTestSupport;

import tools.jackson.databind.JsonNode;

class PaperTradingControllerTest extends IntegrationTestSupport {

    @Autowired
    private PaperTradingService paperTradingService;

    @MockitoBean
    private MarketPriceProvider marketPriceProvider;

    @BeforeEach
    void setUpMarketPrice() {
        given(marketPriceProvider.getLatestPrice(anyString())).willReturn(new BigDecimal("1000"));
        given(marketPriceProvider.getLatestPrices(List.of("KRW-BTC")))
                .willReturn(Map.of("KRW-BTC", new BigDecimal("1000")));
    }

    @Test
    void 매수_매도_후_포트폴리오와_주문이력을_조회한다() throws Exception {
        String accessToken = registerAndLogin();

        JsonNode buyOrder = data(order(accessToken, "KRW-BTC", "BUY", "2")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.statusCode").value(201))
                .andExpect(jsonPath("$.data.symbol").value("KRW-BTC"))
                .andExpect(jsonPath("$.data.side").value("BUY")));
        assertThat(buyOrder.get("orderId").asLong()).isPositive();
        assertThat(buyOrder.get("amount").decimalValue()).isEqualByComparingTo("2000");

        JsonNode sellOrder = data(order(accessToken, "KRW-BTC", "SELL", "1")
                .andExpect(status().isCreated()));
        assertThat(sellOrder.get("amount").decimalValue()).isEqualByComparingTo("1000");

        JsonNode portfolio = data(mockMvc.perform(get("/api/paper/portfolio")
                        .header("Authorization", bearer(accessToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.currency").value("KRW")));
        assertThat(portfolio.get("cashBalance").decimalValue()).isEqualByComparingTo("9999000");
        assertThat(portfolio.get("totalPurchaseAmount").decimalValue()).isEqualByComparingTo("1000");
        assertThat(portfolio.get("totalEvaluationAmount").decimalValue()).isEqualByComparingTo("1000");
        assertThat(portfolio.get("totalProfitLoss").decimalValue()).isEqualByComparingTo("0");
        assertThat(portfolio.get("totalReturnRate").decimalValue()).isEqualByComparingTo("0.0000");
        assertThat(portfolio.get("totalAssetAmount").decimalValue()).isEqualByComparingTo("10000000");
        assertThat(portfolio.get("holdings").get(0).get("symbol").asString()).isEqualTo("KRW-BTC");
        assertThat(portfolio.get("holdings").get(0).get("quantity").decimalValue()).isEqualByComparingTo("1");
        assertThat(portfolio.get("holdings").get(0).get("averagePurchasePrice").decimalValue())
                .isEqualByComparingTo("1000");
        assertThat(portfolio.get("holdings").get(0).get("currentPrice").decimalValue()).isEqualByComparingTo("1000");
        assertThat(portfolio.get("holdings").get(0).get("evaluationAmount").decimalValue())
                .isEqualByComparingTo("1000");
        assertThat(portfolio.get("holdings").get(0).get("profitLoss").decimalValue()).isEqualByComparingTo("0");
        assertThat(portfolio.get("holdings").get(0).get("returnRate").decimalValue())
                .isEqualByComparingTo("0.0000");

        mockMvc.perform(get("/api/paper/orders")
                        .header("Authorization", bearer(accessToken))
                        .queryParam("symbol", "krw-btc")
                        .queryParam("page", "0")
                        .queryParam("size", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.orders.length()").value(1))
                .andExpect(jsonPath("$.data.orders[0].side").value("SELL"))
                .andExpect(jsonPath("$.data.pageInfo.page").value(0))
                .andExpect(jsonPath("$.data.pageInfo.size").value(1))
                .andExpect(jsonPath("$.data.pageInfo.totalElements").value(2))
                .andExpect(jsonPath("$.data.pageInfo.totalPages").value(2));
    }

    @Test
    void 잔고나_보유수량이_부족하면_409이고_주문이_생기지_않는다() throws Exception {
        String accessToken = registerAndLogin();
        given(marketPriceProvider.getLatestPrice("KRW-BTC")).willReturn(new BigDecimal("10000001"));

        order(accessToken, "KRW-BTC", "BUY", "1")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("INSUFFICIENT_BALANCE"));
        order(accessToken, "KRW-BTC", "SELL", "1")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("INSUFFICIENT_HOLDING"));

        assertThat(paperOrderRepository.count()).isZero();
        PaperAccount account = paperAccountRepository.findAll().getFirst();
        assertThat(account.getBalance()).isEqualByComparingTo(new BigDecimal("10000000"));
    }

    @Test
    void 초기화는_모든_보유자산과_주문이력을_지우고_초기잔고로_되돌린다() throws Exception {
        String accessToken = registerAndLogin();
        order(accessToken, "KRW-ETH", "BUY", "3").andExpect(status().isCreated());

        JsonNode reset = data(mockMvc.perform(post("/api/paper/reset").header("Authorization", bearer(accessToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.holdings").isEmpty()));
        assertThat(reset.get("cashBalance").decimalValue()).isEqualByComparingTo("10000000");

        mockMvc.perform(get("/api/paper/orders").header("Authorization", bearer(accessToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.orders").isEmpty())
                .andExpect(jsonPath("$.data.pageInfo.totalElements").value(0));
    }

    @Test
    void 잘못된_주문_본문과_페이지는_400_INVALID_REQUEST다() throws Exception {
        String accessToken = registerAndLogin();

        order(accessToken, "BTC", "BUY", "0")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_REQUEST"));
        mockMvc.perform(get("/api/paper/orders")
                        .header("Authorization", bearer(accessToken))
                        .queryParam("page", "-1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_REQUEST"));
    }

    @Test
    void 거래소에_없는_종목은_404_MARKET_NOT_FOUND다() throws Exception {
        String accessToken = registerAndLogin();
        willThrow(new BusinessException(com.ove.global.exception.ErrorCode.MARKET_NOT_FOUND))
                .given(marketPriceProvider).validateMarket("KRW-ZZZZ");

        order(accessToken, "KRW-ZZZZ", "BUY", "1")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("MARKET_NOT_FOUND"));
        assertThat(paperOrderRepository.count()).isZero();
    }

    @Test
    void 반올림_후_0원인_주문은_400_ORDER_AMOUNT_TOO_SMALL이다() throws Exception {
        String accessToken = registerAndLogin();
        given(marketPriceProvider.getLatestPrice("KRW-BTC")).willReturn(new BigDecimal("0.00000001"));

        order(accessToken, "KRW-BTC", "BUY", "0.000000000001")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("ORDER_AMOUNT_TOO_SMALL"));
    }

    @Test
    void 요청에_price가_포함되어도_서버_현재가로만_체결한다() throws Exception {
        String accessToken = registerAndLogin();

        JsonNode orderResponse = data(mockMvc.perform(post("/api/paper/orders")
                        .header("Authorization", bearer(accessToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(toJson(Map.of(
                                "symbol", "KRW-BTC",
                                "side", "BUY",
                                "quantity", "1",
                                "price", "0.00000001"))))
                .andExpect(status().isCreated()));

        assertThat(orderResponse.get("price").decimalValue()).isEqualByComparingTo("1000");
        assertThat(orderResponse.get("amount").decimalValue()).isEqualByComparingTo("1000");
    }

    @Test
    void 같은_계좌의_동시_매수는_한_건만_체결되어_잔고가_음수가_되지_않는다() throws Exception {
        registerAndLogin();
        UUID userId = userRepository.findAll().getFirst().getId();
        given(marketPriceProvider.getLatestPrice("KRW-BTC")).willReturn(new BigDecimal("10000000"));

        ExecutorService executorService = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            ArrayList<Future<Boolean>> results = new ArrayList<>();
            for (int index = 0; index < 2; index++) {
                results.add(executorService.submit(() -> {
                    ready.countDown();
                    start.await();
                    try {
                        paperTradingService.placeOrder(userId, PaperOrderRequest.builder()
                                .symbol("KRW-BTC")
                                .side(OrderSide.BUY)
                                .quantity(BigDecimal.ONE)
                                .build());
                        return true;
                    } catch (BusinessException e) {
                        return false;
                    }
                }));
            }
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            long successCount = 0;
            for (Future<Boolean> result : results) {
                if (result.get(10, TimeUnit.SECONDS)) {
                    successCount++;
                }
            }

            assertThat(successCount).isEqualTo(1);
            assertThat(paperAccountRepository.findByUserId(userId).orElseThrow().getBalance())
                    .isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(paperOrderRepository.count()).isEqualTo(1);
        } finally {
            executorService.shutdownNow();
        }
    }

    private ResultActions order(String accessToken, String symbol, String side, String quantity)
            throws Exception {
        return mockMvc.perform(post("/api/paper/orders")
                .header("Authorization", bearer(accessToken))
                .contentType(MediaType.APPLICATION_JSON)
                .content(toJson(Map.of(
                        "symbol", symbol,
                        "side", side,
                        "quantity", quantity))));
    }

    private String registerAndLogin() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String email = suffix + "@ove.com";
        Map<String, Object> registerRequest = new HashMap<>();
        registerRequest.put("email", email);
        registerRequest.put("password", "password123");
        registerRequest.put("passwordConfirm", "password123");
        registerRequest.put("nickname", "닉" + suffix.substring(0, 6));
        registerRequest.put("termsAgreed", true);
        registerRequest.put("privacyAgreed", true);
        registerRequest.put("investmentNoticeAgreed", true);

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(toJson(registerRequest)))
                .andExpect(status().isCreated());
        JsonNode login = data(mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(toJson(Map.of("email", email, "password", "password123"))))
                .andExpect(status().isOk()));
        return login.get("accessToken").asString();
    }

    private JsonNode data(ResultActions actions) throws Exception {
        return objectMapper.readTree(actions.andReturn().getResponse().getContentAsString()).get("data");
    }

    private String bearer(String accessToken) {
        return "Bearer " + accessToken;
    }
}
