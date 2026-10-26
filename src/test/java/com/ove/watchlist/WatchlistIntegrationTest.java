package com.ove.watchlist;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.util.UUID;
import java.util.List;
import static org.mockito.BDDMockito.given;
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
import com.ove.watchlist.repository.WatchlistRepository;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class WatchlistIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired JwtProvider jwt;
    @Autowired UserRepository users;
    @Autowired WatchlistRepository watchlist;
    @MockitoBean TokenRedisRepository tokens;
    @MockitoBean com.ove.market.service.MarketService marketService;
    String access;
    UUID userId;

    @BeforeEach
    void setUp() {
        var market = new com.ove.market.dto.MarketResponse();
        org.springframework.test.util.ReflectionTestUtils.setField(market, "symbol", "KRW-BTC");
        org.springframework.test.util.ReflectionTestUtils.setField(market, "koreanName", "비트코인");
        org.springframework.test.util.ReflectionTestUtils.setField(market, "price", new java.math.BigDecimal("98500000"));
        given(marketService.getMarkets(null, null, null, 0, 100))
            .willReturn(new com.ove.market.dto.MarketListResponse(List.of(market), 0, 100, 1));
        User user = users.saveAndFlush(User.builder().email("watch@ove.com").nickname("관심사용자").password("hashed").build());
        userId = user.getId();
        access = jwt.createAccessToken(userId).token();
    }

    @Test
    void 등록_조회_통계_삭제_흐름() throws Exception {
        mvc.perform(get("/api/watchlist").header("Authorization", "Bearer " + access))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.items").isEmpty());
        add(" krw-btc ", access, 201);
        mvc.perform(get("/api/watchlist").header("Authorization", "Bearer " + access))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.items[0].symbol").value("KRW-BTC"))
            .andExpect(jsonPath("$.data.items[0].addedAt").isString())
            .andExpect(jsonPath("$.data.items[0].koreanName").value("비트코인"))
            .andExpect(jsonPath("$.data.items[0].price").value(98500000))
            .andExpect(jsonPath("$.data.items[0].signal").isEmpty())
            .andExpect(jsonPath("$.path").value("/api/watchlist"));
        mvc.perform(get("/api/users/me").header("Authorization", "Bearer " + access))
            .andExpect(jsonPath("$.data.stats.watchlistCount").value(1));
        mvc.perform(delete("/api/watchlist/krw-btc").header("Authorization", "Bearer " + access))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data").isEmpty());
        assertThat(watchlist.countByUserId(userId)).isZero();
    }

    @Test
    void 사용자별_목록과_삭제가_분리된다() throws Exception {
        add("KRW-BTC", access, 201);
        User other = users.saveAndFlush(User.builder().email("other@ove.com").nickname("다른사용자").password("hashed").build());
        String otherToken = jwt.createAccessToken(other.getId()).token();
        mvc.perform(get("/api/watchlist").header("Authorization", "Bearer " + otherToken))
            .andExpect(jsonPath("$.data.items").isEmpty());
        mvc.perform(delete("/api/watchlist/KRW-BTC").header("Authorization", "Bearer " + otherToken))
            .andExpect(status().isNotFound());
        add("KRW-BTC", otherToken, 201);
        assertThat(watchlist.countByUserId(userId)).isEqualTo(1);
        assertThat(watchlist.countByUserId(other.getId())).isEqualTo(1);
    }

    @Test
    void 중복등록은_409() throws Exception {
        add("KRW-BTC", access, 201);
        add("krw-btc", access, 409);
        assertThat(watchlist.countByUserId(userId)).isEqualTo(1);
    }

    @Test
    void 잘못된_종목과_빈_입력은_거절한다() throws Exception {
        add("005930", access, 404);
        add("", access, 400);
        mvc.perform(post("/api/watchlist").header("Authorization", "Bearer " + access)
            .contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isBadRequest());
    }

    @Test
    void 모든_경로는_인증이_필요하다() throws Exception {
        mvc.perform(get("/api/watchlist")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/watchlist").contentType(MediaType.APPLICATION_JSON).content("{\"symbol\":\"KRW-BTC\"}"))
            .andExpect(status().isUnauthorized());
        mvc.perform(delete("/api/watchlist/KRW-BTC")).andExpect(status().isUnauthorized());
    }

    @Test
    void 삭제된_사용자와_없는_관심종목은_404() throws Exception {
        String missingUser = jwt.createAccessToken(UUID.randomUUID()).token();
        mvc.perform(get("/api/watchlist").header("Authorization", "Bearer " + missingUser))
            .andExpect(status().isNotFound()).andExpect(jsonPath("$.error").value("USER_NOT_FOUND"));
        mvc.perform(delete("/api/watchlist/KRW-BTC").header("Authorization", "Bearer " + access))
            .andExpect(status().isNotFound()).andExpect(jsonPath("$.error").value("WATCHLIST_NOT_FOUND"));
    }

    private void add(String symbol, String token, int statusCode) throws Exception {
        mvc.perform(post("/api/watchlist").header("Authorization", "Bearer " + token)
            .contentType(MediaType.APPLICATION_JSON).content("{\"symbol\":\"" + symbol + "\"}"))
            .andExpect(status().is(statusCode));
    }
}
