package com.ove.watchlist.controller;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.ove.auth.repository.TokenRedisRepository;
import com.ove.global.exception.BusinessException;
import com.ove.global.exception.ErrorCode;
import com.ove.global.security.JwtProvider;
import com.ove.market.client.UpbitClient;
import com.ove.market.client.UpbitMarketResponse;
import com.ove.market.client.UpbitTickerResponse;
import com.ove.user.domain.User;
import com.ove.user.repository.UserRepository;
import com.ove.watchlist.repository.WatchlistRepository;

@SpringBootTest
@AutoConfigureMockMvc
class WatchlistControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtProvider jwtProvider;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private WatchlistRepository watchlistRepository;

    @MockitoBean
    private UpbitClient upbitClient;

    @MockitoBean
    private TokenRedisRepository tokenRedisRepository;

    private String myToken;
    private String otherToken;

    // 테스트마다 사용자 두 명과 가짜 업비트 종목·현재가를 준비할 때 사용
    @BeforeEach
    void setUp() {
        myToken = tokenOf(saveUser("me@ove.com", "나"));
        otherToken = tokenOf(saveUser("other@ove.com", "남"));

        given(upbitClient.getMarkets()).willReturn(List.of(
                market("KRW-BTC", "비트코인", "Bitcoin"),
                market("KRW-ETH", "이더리움", "Ethereum"),
                market("KRW-XRP", "엑스알피", "XRP"),
                market("KRW-DOGE", "도지코인", "Dogecoin")));
        given(upbitClient.getTickers(anyList())).willReturn(List.of(
                ticker("KRW-BTC", "98500000", "0.0123"),
                ticker("KRW-ETH", "3279000", "-0.02"),
                ticker("KRW-XRP", "1778", "0.005")));
    }

    // 테스트가 끝날 때마다 만든 데이터를 지울 때 사용
    @AfterEach
    void cleanUp() {
        watchlistRepository.deleteAllInBatch();
        userRepository.deleteAllInBatch();
    }

    @Test
    @DisplayName("관심 종목: 로그인하지 않으면 401")
    void needLogin() throws Exception {
        mockMvc.perform(get("/api/watchlist"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("UNAUTHORIZED"));
    }

    @Test
    @DisplayName("관심 종목 등록: 201과 함께 등록한 종목이 나온다 (소문자도 대문자로 맞춘다)")
    void add() throws Exception {
        add(myToken, "krw-btc")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.statusCode").value(201))
                .andExpect(jsonPath("$.message").value("관심 종목에 추가했습니다."))
                .andExpect(jsonPath("$.data.symbol").value("KRW-BTC"))
                .andExpect(jsonPath("$.data.addedAt").value(notNullValue()));
    }

    @Test
    @DisplayName("업비트가 안 될 때: 등록은 되고, 목록은 이름·가격만 비운 채 나온다")
    void upbitDown() throws Exception {
        given(upbitClient.getMarkets()).willThrow(new BusinessException(ErrorCode.UPSTREAM_UNAVAILABLE));
        given(upbitClient.getTickers(anyList())).willThrow(new BusinessException(ErrorCode.UPSTREAM_UNAVAILABLE));

        add(myToken, "KRW-BTC").andExpect(status().isCreated());

        mockMvc.perform(get("/api/watchlist").header("Authorization", "Bearer " + myToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items", hasSize(1)))
                .andExpect(jsonPath("$.data.items[0].symbol").value("KRW-BTC"))
                .andExpect(jsonPath("$.data.items[0].koreanName").value(nullValue()))
                .andExpect(jsonPath("$.data.items[0].price").value(nullValue()))
                .andExpect(jsonPath("$.data.items[0].addedAt").value(notNullValue()));
    }

    @Test
    @DisplayName("내 정보: stats.watchlistCount에 관심 종목 수가 나온다")
    void myInfoWatchlistCount() throws Exception {
        add(myToken, "KRW-BTC");
        add(myToken, "KRW-ETH");
        add(otherToken, "KRW-XRP");

        mockMvc.perform(get("/api/users/me").header("Authorization", "Bearer " + myToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.stats.watchlistCount").value(2));
    }

    @Test
    @DisplayName("관심 종목 등록: 같은 종목은 409, 제공하지 않는 종목은 404, 심볼이 없으면 400")
    void addError() throws Exception {
        add(myToken, "KRW-BTC").andExpect(status().isCreated());

        add(myToken, "KRW-BTC")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("WATCHLIST_ALREADY_EXISTS"));

        add(myToken, "KRW-DOGE")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("MARKET_NOT_FOUND"));

        add(myToken, "")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_REQUEST"));
    }

    @Test
    @DisplayName("관심 종목 목록: 등록한 순서대로 현재가와 같이 나오고, 다른 사람 것은 안 보인다")
    void getMyWatchlist() throws Exception {
        add(myToken, "KRW-ETH");
        add(myToken, "KRW-BTC");
        add(otherToken, "KRW-XRP");

        mockMvc.perform(get("/api/watchlist").header("Authorization", "Bearer " + myToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("요청에 성공했습니다."))
                .andExpect(jsonPath("$.data.items", hasSize(2)))
                .andExpect(jsonPath("$.data.items[0].symbol").value("KRW-ETH"))
                .andExpect(jsonPath("$.data.items[0].koreanName").value("이더리움"))
                .andExpect(jsonPath("$.data.items[0].price").value(3279000))
                .andExpect(jsonPath("$.data.items[0].changeRate").value(-0.02))
                .andExpect(jsonPath("$.data.items[0].signal").value(nullValue()))
                .andExpect(jsonPath("$.data.items[0].addedAt").value(notNullValue()))
                .andExpect(jsonPath("$.data.items[1].symbol").value("KRW-BTC"))
                .andExpect(jsonPath("$.data.totalElements").value(2));
    }

    @Test
    @DisplayName("관심 종목 삭제: 지우면 목록에서 빠지고, 없는 종목을 지우면 404")
    void remove() throws Exception {
        add(myToken, "KRW-BTC");

        mockMvc.perform(delete("/api/watchlist/KRW-BTC").header("Authorization", "Bearer " + myToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("관심 종목에서 삭제했습니다."))
                .andExpect(jsonPath("$.data").value(nullValue()));

        mockMvc.perform(get("/api/watchlist").header("Authorization", "Bearer " + myToken))
                .andExpect(jsonPath("$.data.items", hasSize(0)));

        mockMvc.perform(delete("/api/watchlist/KRW-BTC").header("Authorization", "Bearer " + myToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("WATCHLIST_NOT_FOUND"));
    }

    // 관심 종목 등록 요청을 보낼 때 사용
    private ResultActions add(String token, String symbol) throws Exception {
        return mockMvc.perform(post("/api/watchlist")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"symbol\":\"" + symbol + "\"}"));
    }

    // 테스트용 사용자를 저장할 때 사용
    private User saveUser(String email, String nickname) {
        return userRepository.save(User.builder()
                .email(email)
                .password("encoded-password")
                .nickname(nickname)
                .build());
    }

    // 사용자의 access 토큰을 만들 때 사용
    private String tokenOf(User user) {
        return jwtProvider.createAccessToken(user.getId()).token();
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
    private UpbitTickerResponse ticker(String code, String price, String changeRate) {
        return UpbitTickerResponse.builder()
                .market(code)
                .tradePrice(new BigDecimal(price))
                .signedChangeRate(new BigDecimal(changeRate))
                .build();
    }
}
