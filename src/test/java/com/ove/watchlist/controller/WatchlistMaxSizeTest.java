package com.ove.watchlist.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.ove.global.security.JwtProvider;
import com.ove.market.client.UpbitClient;
import com.ove.market.client.UpbitMarketResponse;
import com.ove.market.client.UpbitTickerResponse;
import com.ove.support.IntegrationTestSupport;
import com.ove.user.domain.User;

/**
 * 최대 개수를 2로 낮춰, 서로 다른 종목을 동시에 등록해도 최대 개수를 넘지 않는지 확인한다.
 */
@TestPropertySource(properties = "watchlist.max-size=2")
class WatchlistMaxSizeTest extends IntegrationTestSupport {

    @Autowired
    private JwtProvider jwtProvider;

    @MockitoBean
    private UpbitClient upbitClient;

    @BeforeEach
    void setUp() {
        given(upbitClient.getMarkets()).willReturn(List.of(
                market("KRW-BTC", "비트코인", "Bitcoin"),
                market("KRW-ETH", "이더리움", "Ethereum"),
                market("KRW-XRP", "엑스알피", "XRP")));
        given(upbitClient.getTickers(anyList())).willReturn(List.of(
                ticker("KRW-BTC"), ticker("KRW-ETH"), ticker("KRW-XRP")));
    }

    @Test
    @DisplayName("최대 개수 초과 등록은 409 WATCHLIST_CONFLICT")
    void exceedsMax() throws Exception {
        String token = tokenOf(createUser());

        for (String symbol : List.of("KRW-BTC", "KRW-ETH")) {
            mockMvc.perform(post("/api/watchlist").header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON).content(toJson(Map.of("symbol", symbol))))
                    .andExpect(status().isCreated());
        }
        mockMvc.perform(post("/api/watchlist").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(toJson(Map.of("symbol", "KRW-XRP"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("WATCHLIST_CONFLICT"));
    }

    @Test
    @DisplayName("서로 다른 종목을 동시에 등록해도 최대 개수(2)를 넘지 않는다")
    void concurrentDifferentSymbols() throws Exception {
        User user = createUser();
        String token = tokenOf(user);
        List<String> symbols = List.of("KRW-BTC", "KRW-ETH", "KRW-XRP");

        ExecutorService pool = Executors.newFixedThreadPool(symbols.size());
        List<Integer> statuses = new ArrayList<>();
        try {
            CountDownLatch ready = new CountDownLatch(symbols.size());
            CountDownLatch go = new CountDownLatch(1);
            List<Future<Integer>> futures = new ArrayList<>();
            for (String symbol : symbols) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    return mockMvc.perform(post("/api/watchlist").header("Authorization", "Bearer " + token)
                                    .contentType(MediaType.APPLICATION_JSON).content(toJson(Map.of("symbol", symbol))))
                            .andReturn().getResponse().getStatus();
                }));
            }
            ready.await();
            go.countDown();
            for (Future<Integer> future : futures) {
                statuses.add(future.get());
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(statuses.stream().filter(s -> s == 201).count()).isEqualTo(2);
        assertThat(statuses.stream().filter(s -> s == 409).count()).isEqualTo(1);
        assertThat(watchlistRepository.countByUserId(user.getId())).isEqualTo(2);
    }

    private User createUser() {
        return userRepository.save(User.builder().email("a@ove.com")
                .password("$2a$10$abcdefghijklmnopqrstuuabcdefghijklmnopqrstuvwxyz01234").nickname("에이").build());
    }

    private String tokenOf(User user) {
        return jwtProvider.createAccessToken(user.getId()).token();
    }

    private UpbitMarketResponse market(String symbol, String koreanName, String englishName) {
        return UpbitMarketResponse.builder().market(symbol).koreanName(koreanName).englishName(englishName).build();
    }

    private UpbitTickerResponse ticker(String symbol) {
        return UpbitTickerResponse.builder().market(symbol).tradePrice(new BigDecimal("1000"))
                .signedChangeRate(new BigDecimal("0.001")).build();
    }
}
