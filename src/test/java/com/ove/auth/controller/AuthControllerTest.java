package com.ove.auth.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import com.ove.account.domain.PaperAccount;
import com.ove.support.IntegrationTestSupport;
import com.ove.user.domain.TermsAgreement;
import com.ove.user.domain.User;

import tools.jackson.databind.JsonNode;

class AuthControllerTest extends IntegrationTestSupport {

    // ---------- register ----------

    @Test
    void 회원가입_성공_201_토큰없이_user만_내려준다() throws Exception {
        register(registerBody("user@ove.com", "오브"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.statusCode").value(201))
                .andExpect(jsonPath("$.path").value("/api/auth/register"))
                .andExpect(jsonPath("$.timestamp").isString())
                .andExpect(jsonPath("$.error").isEmpty())
                .andExpect(jsonPath("$.data.user.userId").isString())
                .andExpect(jsonPath("$.data.user.email").value("user@ove.com"))
                .andExpect(jsonPath("$.data.user.nickname").value("오브"))
                .andExpect(jsonPath("$.data.accessToken").doesNotExist());

        User user = userRepository.findByEmail("user@ove.com").orElseThrow();
        assertThat(user.getPassword()).startsWith("$2");
        assertThat(termsAgreementRepository.findAllByUserId(user.getId()))
                .hasSize(3).allMatch(TermsAgreement::isAgreed);
        PaperAccount account = paperAccountRepository.findByUserId(user.getId()).orElseThrow();
        assertThat(account.getBalance()).isEqualByComparingTo(new BigDecimal("10000000"));
        assertThat(account.getCurrency()).isEqualTo("KRW");
    }

    @Test
    void 회원가입_검증실패_400_INVALID_REQUEST() throws Exception {
        Map<String, Object> body = registerBody("not-an-email", "오브");
        body.put("password", "short");
        body.put("investmentNoticeAgreed", false);

        register(body)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.data").isEmpty());
    }

    @Test
    void 회원가입_비밀번호확인_불일치_400_INVALID_REQUEST() throws Exception {
        Map<String, Object> body = registerBody("user@ove.com", "오브");
        body.put("passwordConfirm", "different123");

        register(body)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_REQUEST"));
    }

    @Test
    void 회원가입_본문이_깨지면_400_INVALID_REQUEST() throws Exception {
        mockMvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content("{broken"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_REQUEST"));
    }

    @Test
    void 회원가입_이메일중복_409_EMAIL_ALREADY_EXISTS() throws Exception {
        register(registerBody("user@ove.com", "오브")).andExpect(status().isCreated());

        register(registerBody("USER@ove.com", "다른닉"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("EMAIL_ALREADY_EXISTS"));
    }

    @Test
    void 회원가입_닉네임중복_409_NICKNAME_ALREADY_EXISTS() throws Exception {
        register(registerBody("user@ove.com", "오브")).andExpect(status().isCreated());

        register(registerBody("other@ove.com", "오브"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("NICKNAME_ALREADY_EXISTS"));
    }

    // ---------- login ----------

    @Test
    void 로그인_성공_200_토큰쌍과_user() throws Exception {
        register(registerBody("user@ove.com", "오브"));

        login("user@ove.com", "password123")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.accessToken").isString())
                .andExpect(jsonPath("$.data.refreshToken").isString())
                .andExpect(jsonPath("$.data.expiresIn").value(1800))
                .andExpect(jsonPath("$.data.user.email").value("user@ove.com"))
                .andExpect(jsonPath("$.error").isEmpty());

        Set<String> keys = redisTemplate.keys("refresh:*");
        assertThat(keys).hasSize(1);
    }

    @Test
    void 로그인_비밀번호_틀림_401_INVALID_CREDENTIALS() throws Exception {
        register(registerBody("user@ove.com", "오브"));

        login("user@ove.com", "wrong12345")
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("INVALID_CREDENTIALS"))
                .andExpect(jsonPath("$.data").isEmpty());
    }

    @Test
    void 로그인_없는_이메일_401_INVALID_CREDENTIALS() throws Exception {
        login("none@ove.com", "password123")
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("INVALID_CREDENTIALS"));
    }

    @Test
    void 여러_기기에서_로그인하면_refresh가_각각_저장된다() throws Exception {
        register(registerBody("user@ove.com", "오브"));

        login("user@ove.com", "password123");
        login("user@ove.com", "password123");

        assertThat(redisTemplate.keys("refresh:*")).hasSize(2);
    }

    // ---------- refresh ----------

    @Test
    void 재발급_성공_새쌍_발급_후_기존_refresh는_재사용불가() throws Exception {
        JsonNode tokens = registerAndLogin();
        String oldRefresh = tokens.get("refreshToken").asString();

        JsonNode refreshed = data(refresh(oldRefresh)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.expiresIn").value(1800)));
        assertThat(refreshed.get("refreshToken").asString()).isNotEqualTo(oldRefresh);

        refresh(oldRefresh)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("INVALID_REFRESH_TOKEN"));

        // 새로 받은 access 토큰으로 인증 API 호출 가능
        mockMvc.perform(get("/api/users/me").header("Authorization", bearer(refreshed.get("accessToken").asString())))
                .andExpect(status().isOk());
    }

    @Test
    void 재발급_access토큰을_보내면_401_INVALID_REFRESH_TOKEN() throws Exception {
        JsonNode tokens = registerAndLogin();

        refresh(tokens.get("accessToken").asString())
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("INVALID_REFRESH_TOKEN"));
    }

    @Test
    void 재발급_위조토큰_401_INVALID_REFRESH_TOKEN() throws Exception {
        refresh("garbage.token.value")
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("INVALID_REFRESH_TOKEN"));
    }

    // ---------- logout ----------

    @Test
    void 로그아웃_후_access는_401_refresh는_재발급불가() throws Exception {
        JsonNode tokens = registerAndLogin();
        String access = tokens.get("accessToken").asString();
        String refreshToken = tokens.get("refreshToken").asString();

        mockMvc.perform(post("/api/auth/logout")
                        .header("Authorization", bearer(access))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(toJson(Map.of("refreshToken", refreshToken))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.statusCode").value(200))
                .andExpect(jsonPath("$.data").isEmpty())
                .andExpect(jsonPath("$.error").isEmpty());

        assertThat(redisTemplate.keys("blacklist:*")).hasSize(1);
        assertThat(redisTemplate.keys("refresh:*")).isEmpty();

        mockMvc.perform(get("/api/users/me").header("Authorization", bearer(access)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("UNAUTHORIZED"));

        refresh(refreshToken)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("INVALID_REFRESH_TOKEN"));
    }

    @Test
    void 로그아웃_토큰없이_호출하면_401_UNAUTHORIZED() throws Exception {
        mockMvc.perform(post("/api/auth/logout")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(toJson(Map.of("refreshToken", "anything"))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("UNAUTHORIZED"))
                .andExpect(jsonPath("$.path").value("/api/auth/logout"));
    }

    // ---------- helpers ----------

    private ResultActions register(Map<String, Object> body) throws Exception {
        return mockMvc.perform(post("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(toJson(body)));
    }

    private ResultActions login(String email, String password) throws Exception {
        return mockMvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(toJson(Map.of("email", email, "password", password))));
    }

    private ResultActions refresh(String refreshToken) throws Exception {
        return mockMvc.perform(post("/api/auth/refresh")
                .contentType(MediaType.APPLICATION_JSON)
                .content(toJson(Map.of("refreshToken", refreshToken))));
    }

    private JsonNode registerAndLogin() throws Exception {
        String email = UUID.randomUUID().toString().substring(0, 8) + "@ove.com";
        register(registerBody(email, "닉" + email.substring(0, 6)));
        return data(login(email, "password123"));
    }

    private JsonNode data(ResultActions actions) throws Exception {
        String json = actions.andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(json).get("data");
    }

    private String bearer(String token) {
        return "Bearer " + token;
    }

    private Map<String, Object> registerBody(String email, String nickname) {
        Map<String, Object> body = new HashMap<>();
        body.put("email", email);
        body.put("password", "password123");
        body.put("passwordConfirm", "password123");
        body.put("nickname", nickname);
        body.put("termsAgreed", true);
        body.put("privacyAgreed", true);
        body.put("investmentNoticeAgreed", true);
        return body;
    }
}
