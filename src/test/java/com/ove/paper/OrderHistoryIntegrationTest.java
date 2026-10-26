package com.ove.paper;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import java.math.BigDecimal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import com.ove.auth.repository.TokenRedisRepository;
import com.ove.global.security.JwtProvider;
import com.ove.user.domain.User;
import com.ove.user.repository.UserRepository;
import com.ove.account.domain.PaperAccount;
import com.ove.account.repository.PaperAccountRepository;
import com.ove.paper.repository.PaperOrderRepository;
import com.ove.paper.domain.PaperOrder;
import com.ove.paper.domain.OrderSide;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class OrderHistoryIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired JwtProvider jwt;
    @Autowired UserRepository users;
    @Autowired PaperAccountRepository accounts;
    @Autowired PaperOrderRepository orders;
    @MockitoBean TokenRedisRepository tokens;
    String token;
    PaperAccount account;

    private PaperAccount account(String suffix) {
        User user = users.saveAndFlush(User.builder().email(suffix+"@test.com").nickname(suffix).password("hash").build());
        return accounts.saveAndFlush(PaperAccount.builder().user(user).balance(new BigDecimal("10000000")).currency("KRW").build());
    }
    @BeforeEach void setUp() {
        account = account("history");
        token = "Bearer " + jwt.createAccessToken(account.getUser().getId()).token();
    }
    private void order(PaperAccount owner, String symbol) {
        orders.saveAndFlush(PaperOrder.builder().paperAccount(owner).symbol(symbol).side(OrderSide.BUY)
            .quantity(new BigDecimal("0.01")).price(new BigDecimal("98500000"))
            .amount(new BigDecimal("985000")).fee(new BigDecimal("492")).signalAtOrder("BUY").build());
    }
    @Test void 비로그인_조회는_401() throws Exception {
        mvc.perform(get("/api/paper/orders")).andExpect(status().isUnauthorized());
    }
    @Test void 빈_내역은_빈_페이지() throws Exception {
        mvc.perform(get("/api/paper/orders").header("Authorization", token))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.items").isEmpty())
            .andExpect(jsonPath("$.data.totalElements").value(0));
    }
    @Test void 내_거래만_조회하고_종목_필터와_페이지가_적용된다() throws Exception {
        order(account, "KRW-BTC"); order(account, "KRW-ETH");
        order(account("other"), "KRW-BTC");
        mvc.perform(get("/api/paper/orders").header("Authorization", token).param("size", "1"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.totalElements").value(2))
            .andExpect(jsonPath("$.data.items[0].symbol").value("KRW-ETH"));
        mvc.perform(get("/api/paper/orders").header("Authorization", token).param("symbol", "krw-btc"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.totalElements").value(1))
            .andExpect(jsonPath("$.data.items[0].quantity").value(.01))
            .andExpect(jsonPath("$.data.items[0].fee").value(492))
            .andExpect(jsonPath("$.data.items[0].signalAtOrder").value("BUY"))
            .andExpect(jsonPath("$.data.items[0].executedAt").isString());
        mvc.perform(get("/api/paper/orders").header("Authorization", token).param("page", "1").param("size", "1"))
            .andExpect(jsonPath("$.data.items[0].symbol").value("KRW-BTC"));
    }
    @Test void 잘못된_페이지_범위는_400() throws Exception {
        mvc.perform(get("/api/paper/orders").header("Authorization", token).param("page", "-1"))
            .andExpect(status().isBadRequest());
        mvc.perform(get("/api/paper/orders").header("Authorization", token).param("size", "101"))
            .andExpect(status().isBadRequest());
    }
}
