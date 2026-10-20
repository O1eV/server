package com.ove.user.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.ove.global.security.JwtProperties;
import com.ove.global.security.JwtProvider;
import com.ove.market.client.UpbitClient;
import com.ove.support.IntegrationTestSupport;

/**
 * /users/me와 Security 설정(permitAll, 401 코드 구분) 통합 테스트.
 */
class UserControllerTest extends IntegrationTestSupport {

    @Autowired
    private JwtProvider jwtProvider;

    @Autowired
    private JwtProperties jwtProperties;

    // 시세 경로 테스트가 진짜 업비트를 부르지 않도록 가짜로 바꾼다.
    @MockitoBean
    private UpbitClient upbitClient;

    @Test
    void 내정보_성공_200() throws Exception {
        String access = registerAndLogin("user@ove.com", "오브");

        mockMvc.perform(get("/api/users/me").header("Authorization", "Bearer " + access))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.path").value("/api/users/me"))
                .andExpect(jsonPath("$.data.user.userId").isString())
                .andExpect(jsonPath("$.data.user.email").value("user@ove.com"))
                .andExpect(jsonPath("$.data.user.nickname").value("오브"))
                .andExpect(jsonPath("$.data.user.createdAt").isString())
                .andExpect(jsonPath("$.data.stats.watchlistCount").value(0))
                .andExpect(jsonPath("$.data.stats.activeApiKeyCount").value(0));
    }

    @Test
    void 내정보_토큰없음_401_UNAUTHORIZED() throws Exception {
        mockMvc.perform(get("/api/users/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.statusCode").value(401))
                .andExpect(jsonPath("$.error").value("UNAUTHORIZED"))
                .andExpect(jsonPath("$.data").isEmpty());
    }

    @Test
    void 내정보_만료토큰_401_TOKEN_EXPIRED() throws Exception {
        JwtProvider expiredProvider = new JwtProvider(new JwtProperties(jwtProperties.secret(),
                Duration.ofSeconds(-10), Duration.ofDays(14)));
        String expired = expiredProvider.createAccessToken(UUID.randomUUID()).token();

        mockMvc.perform(get("/api/users/me").header("Authorization", "Bearer " + expired))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("TOKEN_EXPIRED"));
    }

    @Test
    void 내정보_위조토큰_401_UNAUTHORIZED() throws Exception {
        mockMvc.perform(get("/api/users/me").header("Authorization", "Bearer forged.token.value"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("UNAUTHORIZED"));
    }

    @Test
    void 내정보_탈퇴등으로_사용자가_없으면_404_USER_NOT_FOUND() throws Exception {
        String access = jwtProvider.createAccessToken(UUID.randomUUID()).token();

        mockMvc.perform(get("/api/users/me").header("Authorization", "Bearer " + access))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("USER_NOT_FOUND"));
    }

    @Test
    void permitAll_시세경로는_토큰없이_통과한다() throws Exception {
        // UpbitClient가 가짜라 종목이 비어 있다. Security에서 401로 막히지 않고 컨트롤러까지 가는 것을 확인한다.
        mockMvc.perform(get("/api/markets"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/markets/search").param("q", "btc"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/markets/KRW-BTC/candles"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("MARKET_NOT_FOUND"));
    }

    @Test
    void permitAll_경로에_잘못된_토큰이_와도_익명으로_통과한다() throws Exception {
        mockMvc.perform(get("/api/markets").header("Authorization", "Bearer forged.token.value"))
                .andExpect(status().isOk());
    }

    @Test
    void permitAll에_없는_경로는_401() throws Exception {
        mockMvc.perform(get("/api/markets/KRW-BTC"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("UNAUTHORIZED"));
        mockMvc.perform(get("/api/candles/KRW-BTC"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void swagger_문서는_토큰없이_열린다() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk());
    }

    private String registerAndLogin(String email, String nickname) throws Exception {
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(toJson(Map.of(
                                "email", email,
                                "password", "password123",
                                "passwordConfirm", "password123",
                                "nickname", nickname,
                                "termsAgreed", true,
                                "privacyAgreed", true,
                                "investmentNoticeAgreed", true))))
                .andExpect(status().isCreated());

        String json = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(toJson(Map.of("email", email, "password", "password123"))))
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(json).get("data").get("accessToken").asString();
    }
}
