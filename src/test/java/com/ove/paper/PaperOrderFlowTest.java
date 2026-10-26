package com.ove.paper;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.mockito.BDDMockito.given;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import java.math.BigDecimal;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import com.ove.auth.repository.TokenRedisRepository;
import com.ove.global.security.JwtProvider;
import com.ove.user.domain.User;
import com.ove.user.repository.UserRepository;
import com.ove.account.domain.PaperAccount;
import com.ove.account.repository.PaperAccountRepository;
import com.ove.market.service.MarketPriceProvider;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class PaperOrderFlowTest {
    @Autowired MockMvc mvc;
    @Autowired JwtProvider jwt;
    @Autowired UserRepository users;
    @Autowired PaperAccountRepository accounts;
    @MockitoBean TokenRedisRepository tokens;
    @MockitoBean MarketPriceProvider prices;
    String token;
    @BeforeEach void setUp() {
        User user = users.saveAndFlush(User.builder().email("paper@test.com").nickname("모의투자").password("hash").build());
        accounts.saveAndFlush(PaperAccount.builder().user(user).balance(new BigDecimal("10000000")).currency("KRW").build());
        token = "Bearer " + jwt.createAccessToken(user.getId()).token();
        given(prices.getLatestPrice(anyString())).willReturn(new BigDecimal("1000000"));
        given(prices.getLatestPrices(anyList())).willAnswer(call -> {
            java.util.List<String> symbols = call.getArgument(0);
            return symbols.stream().collect(java.util.stream.Collectors.toMap(value -> value, value -> new BigDecimal("1000000")));
        });
    }
    private org.springframework.test.web.servlet.ResultActions order(String side, String quantity) throws Exception {
        return mvc.perform(post("/api/paper/orders").header("Authorization", token).contentType(MediaType.APPLICATION_JSON)
            .content("{\"symbol\":\"KRW-BTC\",\"side\":\""+side+"\",\"orderType\":\"MARKET\",\"quantity\":"+quantity+",\"price\":1}"));
    }
    @Test void 매수_매도_잔고_수수료_보유_내역_전체흐름() throws Exception {
        order("BUY", "0.01").andExpect(status().isCreated())
            .andExpect(jsonPath("$.data.price").value(1000000))
            .andExpect(jsonPath("$.data.fee").value(5));
        mvc.perform(get("/api/paper/portfolio").header("Authorization", token))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.cashBalance").value(9989995))
            .andExpect(jsonPath("$.data.totalPurchaseAmount").value(10000))
            .andExpect(jsonPath("$.data.holdings[0].quantity").value(.01));
        order("SELL", "0.005").andExpect(status().isCreated()).andExpect(jsonPath("$.data.fee").value(2.5));
        mvc.perform(get("/api/paper/portfolio").header("Authorization", token))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.cashBalance").value(9994992.5))
            .andExpect(jsonPath("$.data.holdings[0].quantity").value(.005));
        mvc.perform(get("/api/paper/orders").header("Authorization", token))
            .andExpect(jsonPath("$.data.totalElements").value(2))
            .andExpect(jsonPath("$.data.items[0].side").value("SELL"));
    }
    @Test void 수수료를_포함한_잔고부족과_보유부족은_거절() throws Exception {
        order("BUY", "10").andExpect(status().isConflict()).andExpect(jsonPath("$.error").value("INSUFFICIENT_BALANCE"));
        order("SELL", "0.01").andExpect(status().isConflict()).andExpect(jsonPath("$.error").value("INSUFFICIENT_HOLDING"));
    }
    @Test void 잘못된_입력과_비로그인은_거절() throws Exception {
        order("BUY", "0").andExpect(status().isBadRequest());
        mvc.perform(post("/api/paper/orders").header("Authorization", token).contentType(MediaType.APPLICATION_JSON)
            .content("{\"symbol\":\"KRW-BTC\",\"side\":\"BUY\",\"orderType\":\"LIMIT\",\"quantity\":1}"))
            .andExpect(status().isBadRequest());
        mvc.perform(get("/api/paper/portfolio")).andExpect(status().isUnauthorized());
    }
    @Test void 시세_조회실패는_주문실패로_전달() throws Exception {
        given(prices.getLatestPrice(anyString())).willThrow(new com.ove.global.exception.BusinessException(com.ove.global.exception.ErrorCode.MARKET_PRICE_UNAVAILABLE));
        order("BUY", "0.01").andExpect(status().isServiceUnavailable());
        mvc.perform(get("/api/paper/orders").header("Authorization", token)).andExpect(jsonPath("$.data.totalElements").value(0));
    }
    @Test void 초기화는_내_보유와_주문만_지우고_초기잔고로_복구() throws Exception {
        order("BUY", "0.01").andExpect(status().isCreated());
        String myToken = token;
        User other = users.saveAndFlush(User.builder().email("other-paper@test.com").nickname("다른회원").password("hash").build());
        accounts.saveAndFlush(PaperAccount.builder().user(other).balance(new BigDecimal("10000000")).currency("KRW").build());
        token = "Bearer " + jwt.createAccessToken(other.getId()).token();
        order("BUY", "0.02").andExpect(status().isCreated());
        token = myToken;
        mvc.perform(post("/api/paper/reset").header("Authorization", token))
            .andExpect(status().isOk()).andExpect(jsonPath("$.message").value("모의 계좌가 초기화되었습니다."))
            .andExpect(jsonPath("$.data.cashBalance").value(10000000))
            .andExpect(jsonPath("$.data.holdings").doesNotExist());
        mvc.perform(get("/api/paper/portfolio").header("Authorization", token))
            .andExpect(jsonPath("$.data.cashBalance").value(10000000))
            .andExpect(jsonPath("$.data.holdings").isEmpty())
            .andExpect(jsonPath("$.data.totalProfitLoss").value(0));
        mvc.perform(get("/api/paper/orders").header("Authorization", token))
            .andExpect(jsonPath("$.data.totalElements").value(0));
        mvc.perform(post("/api/paper/reset").header("Authorization", token)).andExpect(status().isOk());
        String otherToken = "Bearer " + jwt.createAccessToken(other.getId()).token();
        mvc.perform(get("/api/paper/portfolio").header("Authorization", otherToken))
            .andExpect(jsonPath("$.data.cashBalance").value(9979990))
            .andExpect(jsonPath("$.data.holdings[0].quantity").value(.02));
        mvc.perform(get("/api/paper/orders").header("Authorization", otherToken))
            .andExpect(jsonPath("$.data.totalElements").value(1));
        mvc.perform(post("/api/paper/reset")).andExpect(status().isUnauthorized());
    }
}
