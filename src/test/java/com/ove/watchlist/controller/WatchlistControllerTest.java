package com.ove.watchlist.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.ove.global.exception.BusinessException;
import com.ove.global.exception.ErrorCode;
import com.ove.global.security.JwtProperties;
import com.ove.global.security.JwtProvider;
import com.ove.market.client.UpbitClient;
import com.ove.market.client.UpbitMarketResponse;
import com.ove.market.client.UpbitTickerResponse;
import com.ove.support.IntegrationTestSupport;
import com.ove.user.domain.User;

/**
 * /api/watchlist Controller·Security 통합 테스트. 업비트는 가짜로 바꾸고 DB는 H2, Redis는 Testcontainers를 쓴다.
 */
class WatchlistControllerTest extends IntegrationTestSupport {

    @Autowired
    private JwtProvider jwtProvider;

    @Autowired
    private JwtProperties jwtProperties;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @MockitoBean
    private UpbitClient upbitClient;

    @BeforeEach
    void setUp() {
        given(upbitClient.getMarkets()).willReturn(List.of(
                market("KRW-BTC", "비트코인", "Bitcoin"),
                market("KRW-ETH", "이더리움", "Ethereum"),
                market("KRW-XRP", "엑스알피", "XRP"),
                market("KRW-DOGE", "도지코인", "Dogecoin")));
        given(upbitClient.getTickers(anyList())).willReturn(List.of(
                ticker("KRW-BTC", "100000000"), ticker("KRW-ETH", "4000000"), ticker("KRW-XRP", "800")));
    }

    @Test
    @DisplayName("토큰 없이 3개 API 모두 401 UNAUTHORIZED")
    void unauthorized() throws Exception {
        mockMvc.perform(post("/api/watchlist").contentType(MediaType.APPLICATION_JSON)
                        .content(toJson(Map.of("symbol", "KRW-BTC"))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("UNAUTHORIZED"));
        mockMvc.perform(get("/api/watchlist"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("UNAUTHORIZED"));
        mockMvc.perform(delete("/api/watchlist/KRW-BTC"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("UNAUTHORIZED"));
    }

    @Test
    @DisplayName("만료 토큰은 401 TOKEN_EXPIRED")
    void expiredToken() throws Exception {
        JwtProvider expiredProvider = new JwtProvider(new JwtProperties(jwtProperties.secret(),
                Duration.ofSeconds(-10), Duration.ofDays(14)));
        String expired = expiredProvider.createAccessToken(UUID.randomUUID()).token();

        mockMvc.perform(get("/api/watchlist").header("Authorization", "Bearer " + expired))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("TOKEN_EXPIRED"));
    }

    @Test
    @DisplayName("추가 201: envelope, 심볼 정규화, addedAt")
    void add() throws Exception {
        String token = tokenOf(createUser("a@ove.com", "에이"));

        mockMvc.perform(post("/api/watchlist").header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(toJson(Map.of("symbol", "krw-btc"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.statusCode").value(201))
                .andExpect(jsonPath("$.path").value("/api/watchlist"))
                .andExpect(jsonPath("$.message").value("관심 종목에 추가되었습니다."))
                .andExpect(jsonPath("$.data.symbol").value("KRW-BTC"))
                .andExpect(jsonPath("$.data.addedAt").isString())
                .andExpect(jsonPath("$.error").isEmpty());
    }

    @Test
    @DisplayName("추가 에러: 없는 종목 404, 중복 409, 빈 symbol 400")
    void addErrors() throws Exception {
        String token = tokenOf(createUser("a@ove.com", "에이"));

        // 업비트에는 있지만 MVP 지원 종목이 아님
        mockMvc.perform(post("/api/watchlist").header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON).content(toJson(Map.of("symbol", "KRW-DOGE"))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("MARKET_NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("종목을 찾을 수 없습니다."))
                .andExpect(jsonPath("$.data").isEmpty());

        add(token, "KRW-BTC");
        mockMvc.perform(post("/api/watchlist").header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON).content(toJson(Map.of("symbol", "krw-btc"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.statusCode").value(409))
                .andExpect(jsonPath("$.error").value("WATCHLIST_CONFLICT"))
                .andExpect(jsonPath("$.message").value("이미 관심 종목에 있습니다."));

        mockMvc.perform(post("/api/watchlist").header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON).content(toJson(Map.of("symbol", " "))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_REQUEST"));
    }

    @Test
    @DisplayName("추가: 업비트 장애면 502 UPSTREAM_UNAVAILABLE")
    void addUpstreamDown() throws Exception {
        String token = tokenOf(createUser("a@ove.com", "에이"));
        given(upbitClient.getMarkets()).willThrow(new BusinessException(ErrorCode.UPSTREAM_UNAVAILABLE));

        mockMvc.perform(post("/api/watchlist").header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON).content(toJson(Map.of("symbol", "KRW-BTC"))))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.error").value("UPSTREAM_UNAVAILABLE"));
    }

    @Test
    @DisplayName("목록 200: 등록 순서대로, 종목명·현재가 포함, signal·confidence는 null")
    void list() throws Exception {
        String token = tokenOf(createUser("a@ove.com", "에이"));
        add(token, "KRW-ETH");
        add(token, "KRW-BTC");

        mockMvc.perform(get("/api/watchlist").header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.statusCode").value(200))
                .andExpect(jsonPath("$.path").value("/api/watchlist"))
                .andExpect(jsonPath("$.message").value("요청에 성공했습니다."))
                .andExpect(jsonPath("$.data.items", hasSize(2)))
                .andExpect(jsonPath("$.data.items[0].symbol").value("KRW-ETH"))
                .andExpect(jsonPath("$.data.items[0].koreanName").value("이더리움"))
                .andExpect(jsonPath("$.data.items[0].price").value(4000000))
                .andExpect(jsonPath("$.data.items[0].signal").isEmpty())
                .andExpect(jsonPath("$.data.items[0].confidence").isEmpty())
                .andExpect(jsonPath("$.data.items[0].addedAt").isString())
                .andExpect(jsonPath("$.data.items[1].symbol").value("KRW-BTC"));
    }

    @Test
    @DisplayName("목록: 다른 사용자의 관심 종목은 섞이지 않는다")
    void listIsolatedPerUser() throws Exception {
        String tokenA = tokenOf(createUser("a@ove.com", "에이"));
        String tokenB = tokenOf(createUser("b@ove.com", "비이"));
        add(tokenA, "KRW-BTC");
        add(tokenB, "KRW-XRP");

        mockMvc.perform(get("/api/watchlist").header("Authorization", bearer(tokenA)))
                .andExpect(jsonPath("$.data.items", hasSize(1)))
                .andExpect(jsonPath("$.data.items[0].symbol").value("KRW-BTC"));
        mockMvc.perform(get("/api/watchlist").header("Authorization", bearer(tokenB)))
                .andExpect(jsonPath("$.data.items", hasSize(1)))
                .andExpect(jsonPath("$.data.items[0].symbol").value("KRW-XRP"));

        // 남의 종목은 지울 수도 없다
        mockMvc.perform(delete("/api/watchlist/KRW-BTC").header("Authorization", bearer(tokenB)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("WATCHLIST_NOT_FOUND"));
    }

    @Test
    @DisplayName("목록: 현재가 조회가 실패해도 200이고 price만 null")
    void listPriceFallback() throws Exception {
        String token = tokenOf(createUser("a@ove.com", "에이"));
        add(token, "KRW-BTC");
        given(upbitClient.getTickers(anyList())).willThrow(new BusinessException(ErrorCode.UPSTREAM_UNAVAILABLE));

        mockMvc.perform(get("/api/watchlist").header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items", hasSize(1)))
                .andExpect(jsonPath("$.data.items[0].symbol").value("KRW-BTC"))
                .andExpect(jsonPath("$.data.items[0].koreanName").value("비트코인"))
                .andExpect(jsonPath("$.data.items[0].price").isEmpty());
    }

    @Test
    @DisplayName("삭제 200: data null, 다시 지우면 404 WATCHLIST_NOT_FOUND, 소문자도 지워진다")
    void remove() throws Exception {
        String token = tokenOf(createUser("a@ove.com", "에이"));
        add(token, "KRW-BTC");

        mockMvc.perform(delete("/api/watchlist/krw-btc").header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.statusCode").value(200))
                .andExpect(jsonPath("$.path").value("/api/watchlist/krw-btc"))
                .andExpect(jsonPath("$.message").value("관심 종목에서 삭제되었습니다."))
                .andExpect(jsonPath("$.data").isEmpty())
                .andExpect(jsonPath("$.error").isEmpty());

        mockMvc.perform(delete("/api/watchlist/KRW-BTC").header("Authorization", bearer(token)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("WATCHLIST_NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("관심 종목에 없는 종목입니다."));
    }

    @Test
    @DisplayName("같은 종목을 동시에 지워도 500 없이 1건만 200, 나머지는 404")
    void concurrentRemove() throws Exception {
        User user = createUser("a@ove.com", "에이");
        String token = tokenOf(user);
        add(token, "KRW-BTC");
        int threads = 8;

        List<Integer> statuses = runConcurrently(threads, i -> {
            try {
                return mockMvc.perform(delete("/api/watchlist/KRW-BTC").header("Authorization", bearer(token)))
                        .andReturn().getResponse().getStatus();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });

        assertThat(statuses.stream().filter(s -> s == 200).count()).isEqualTo(1);
        assertThat(statuses.stream().filter(s -> s == 404).count()).isEqualTo(threads - 1);
        assertThat(watchlistRepository.countByUserId(user.getId())).isZero();
    }

    @Test
    @DisplayName("/users/me: stats.watchlistCount가 내 관심 종목 수이고 다른 사용자 것은 세지 않는다")
    void myInfoWatchlistCount() throws Exception {
        String tokenA = tokenOf(createUser("a@ove.com", "에이"));
        String tokenB = tokenOf(createUser("b@ove.com", "비이"));
        add(tokenA, "KRW-BTC");
        add(tokenA, "KRW-ETH");
        add(tokenB, "KRW-XRP");

        mockMvc.perform(get("/api/users/me").header("Authorization", bearer(tokenA)))
                .andExpect(jsonPath("$.data.stats.watchlistCount").value(2));
        mockMvc.perform(get("/api/users/me").header("Authorization", bearer(tokenB)))
                .andExpect(jsonPath("$.data.stats.watchlistCount").value(1));

        mockMvc.perform(delete("/api/watchlist/KRW-BTC").header("Authorization", bearer(tokenA))).andExpect(status().isOk());
        mockMvc.perform(get("/api/users/me").header("Authorization", bearer(tokenA)))
                .andExpect(jsonPath("$.data.stats.watchlistCount").value(1));
    }

    @Test
    @DisplayName("같은 회원이 같은 종목을 동시에 등록하면 1건만 저장되고 나머지는 409")
    void concurrentSameSymbol() throws Exception {
        User user = createUser("a@ove.com", "에이");
        String token = tokenOf(user);
        int threads = 8;

        List<Integer> statuses = runConcurrently(threads, i -> postStatus(token, "KRW-BTC"));

        assertThat(statuses.stream().filter(s -> s == 201).count()).isEqualTo(1);
        assertThat(statuses.stream().filter(s -> s == 409).count()).isEqualTo(threads - 1);
        assertThat(watchlistRepository.countByUserId(user.getId())).isEqualTo(1);
    }

    @Test
    @DisplayName("회원 삭제용 벌크 삭제는 해당 회원 것만 지운다")
    void deleteAllByUserId() throws Exception {
        User a = createUser("a@ove.com", "에이");
        User b = createUser("b@ove.com", "비이");
        add(tokenOf(a), "KRW-BTC");
        add(tokenOf(a), "KRW-ETH");
        add(tokenOf(b), "KRW-BTC");

        // 벌크 삭제는 호출하는 Service(@Transactional)의 트랜잭션 안에서 쓰는 메서드라 여기서도 트랜잭션을 연다.
        Integer deleted = new TransactionTemplate(transactionManager)
                .execute(txStatus -> watchlistRepository.deleteAllByUserId(a.getId()));

        assertThat(deleted).isEqualTo(2);
        assertThat(watchlistRepository.countByUserId(a.getId())).isZero();
        assertThat(watchlistRepository.countByUserId(b.getId())).isEqualTo(1);
    }

    // 스레드를 한꺼번에 출발시켜 결과를 모을 때 사용
    private <T> List<T> runConcurrently(int threads, java.util.function.IntFunction<T> task) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            CountDownLatch ready = new CountDownLatch(threads);
            CountDownLatch go = new CountDownLatch(1);
            List<Future<T>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                int index = i;
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    return task.apply(index);
                }));
            }
            ready.await();
            go.countDown();
            List<T> results = new ArrayList<>();
            for (Future<T> future : futures) {
                results.add(future.get());
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }

    private int postStatus(String token, String symbol) {
        try {
            return mockMvc.perform(post("/api/watchlist").header("Authorization", bearer(token))
                            .contentType(MediaType.APPLICATION_JSON).content(toJson(Map.of("symbol", symbol))))
                    .andReturn().getResponse().getStatus();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private void add(String token, String symbol) throws Exception {
        mockMvc.perform(post("/api/watchlist").header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON).content(toJson(Map.of("symbol", symbol))))
                .andExpect(status().isCreated());
    }

    private User createUser(String email, String nickname) {
        return userRepository.save(User.builder().email(email)
                .password("$2a$10$abcdefghijklmnopqrstuuabcdefghijklmnopqrstuvwxyz01234").nickname(nickname).build());
    }

    private String tokenOf(User user) {
        return jwtProvider.createAccessToken(user.getId()).token();
    }

    private String bearer(String token) {
        return "Bearer " + token;
    }

    private UpbitMarketResponse market(String symbol, String koreanName, String englishName) {
        return UpbitMarketResponse.builder().market(symbol).koreanName(koreanName).englishName(englishName).build();
    }

    private UpbitTickerResponse ticker(String symbol, String price) {
        return UpbitTickerResponse.builder().market(symbol).tradePrice(new BigDecimal(price))
                .signedChangeRate(new BigDecimal("0.001")).build();
    }
}
