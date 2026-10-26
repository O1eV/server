package com.ove.auth.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockCookie;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

import com.ove.account.domain.PaperAccount;
import com.ove.support.IntegrationTestSupport;
import com.ove.user.domain.TermsAgreement;
import com.ove.user.domain.User;

import jakarta.servlet.http.Cookie;
import tools.jackson.databind.JsonNode;

class AuthControllerTest extends IntegrationTestSupport {

    private static final String COOKIE_NAME = "refreshToken";
    private static final String COOKIE_PATH = "/api/auth";
    private static final String ALLOWED_ORIGIN = "http://localhost:5173";
    private static final String EVIL_ORIGIN = "https://evil.example.com";
    private static final int FOURTEEN_DAYS_SECONDS = 1209600;

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
    void 로그인_성공_200_본문에는_refreshToken이_없고_쿠키로_내려준다() throws Exception {
        register(registerBody("user@ove.com", "오브"));

        MvcResult result = login("user@ove.com", "password123", null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.accessToken").isString())
                .andExpect(jsonPath("$.data.refreshToken").doesNotExist())
                .andExpect(jsonPath("$.data.expiresIn").value(1800))
                .andExpect(jsonPath("$.data.user.email").value("user@ove.com"))
                .andExpect(jsonPath("$.error").isEmpty())
                .andReturn();

        // rememberMe를 생략하면 false로 보고 Max-Age가 없는 세션 쿠키로 내려간다.
        MockCookie cookie = refreshCookieOf(result);
        assertThat(cookie.getValue()).isNotBlank();
        assertThat(cookie.isHttpOnly()).isTrue();
        assertThat(cookie.getSecure()).isTrue();
        assertThat(cookie.getSameSite()).isEqualTo("Lax");
        assertThat(cookie.getPath()).isEqualTo(COOKIE_PATH);
        assertThat(cookie.getMaxAge()).isEqualTo(-1);
        assertThat(redisTemplate.keys("refresh:*")).hasSize(1);
    }

    @Test
    void 로그인_rememberMe_true면_Max_Age_14일_쿠키() throws Exception {
        register(registerBody("user@ove.com", "오브"));

        MockCookie cookie = refreshCookieOf(login("user@ove.com", "password123", true)
                .andExpect(status().isOk()).andReturn());

        assertThat(cookie.getMaxAge()).isEqualTo(FOURTEEN_DAYS_SECONDS);
        assertThat(cookie.isHttpOnly()).isTrue();
        assertThat(cookie.getPath()).isEqualTo(COOKIE_PATH);
    }

    @Test
    void 로그인_rememberMe_false면_세션_쿠키() throws Exception {
        register(registerBody("user@ove.com", "오브"));

        MockCookie cookie = refreshCookieOf(login("user@ove.com", "password123", false)
                .andExpect(status().isOk()).andReturn());

        assertThat(cookie.getMaxAge()).isEqualTo(-1);
        // 브라우저 쿠키만 세션이고, 서버(Redis)의 refresh 만료는 14일 그대로다.
        String redisKey = redisTemplate.keys("refresh:*").iterator().next();
        assertThat(redisTemplate.getExpire(redisKey)).isGreaterThan(FOURTEEN_DAYS_SECONDS - 60);
    }

    @Test
    void 로그인_비밀번호_틀림_401_INVALID_CREDENTIALS_쿠키없음() throws Exception {
        register(registerBody("user@ove.com", "오브"));

        login("user@ove.com", "wrong12345", null)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("INVALID_CREDENTIALS"))
                .andExpect(jsonPath("$.data").isEmpty())
                .andExpect(header().doesNotExist("Set-Cookie"));
    }

    @Test
    void 로그인_없는_이메일_401_INVALID_CREDENTIALS() throws Exception {
        login("none@ove.com", "password123", null)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("INVALID_CREDENTIALS"));
    }

    @Test
    void 여러_기기에서_로그인하면_refresh가_각각_저장된다() throws Exception {
        register(registerBody("user@ove.com", "오브"));

        login("user@ove.com", "password123", null);
        login("user@ove.com", "password123", null);

        assertThat(redisTemplate.keys("refresh:*")).hasSize(2);
    }

    // ---------- refresh ----------

    @Test
    void 재발급_성공_새_쿠키로_교체되고_기존_refresh는_재사용불가() throws Exception {
        LoginSession session = registerAndLogin(false);

        MvcResult result = refresh(session.refreshCookie())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.accessToken").isString())
                .andExpect(jsonPath("$.data.refreshToken").doesNotExist())
                .andExpect(jsonPath("$.data.expiresIn").value(1800))
                .andReturn();

        MockCookie newCookie = refreshCookieOf(result);
        assertThat(newCookie.getValue()).isNotEqualTo(session.refreshCookie().getValue());
        assertThat(newCookie.isHttpOnly()).isTrue();
        assertThat(newCookie.getPath()).isEqualTo(COOKIE_PATH);

        refresh(session.refreshCookie())
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("INVALID_REFRESH_TOKEN"));

        // 새로 받은 access 토큰으로 인증 API 호출 가능
        String newAccess = objectMapper.readTree(result.getResponse().getContentAsString())
                .get("data").get("accessToken").asString();
        mockMvc.perform(get("/api/users/me").header("Authorization", "Bearer " + newAccess))
                .andExpect(status().isOk());
        // 새 쿠키로 다시 재발급할 수 있다(rotation 체인).
        refresh(newCookie).andExpect(status().isOk());
    }

    @Test
    void 재발급해도_rememberMe_모드가_이어진다() throws Exception {
        LoginSession remembered = registerAndLogin(true);
        LoginSession sessionOnly = registerAndLogin(false);

        MockCookie rememberedCookie = refreshCookieOf(refresh(remembered.refreshCookie())
                .andExpect(status().isOk()).andReturn());
        MockCookie sessionCookie = refreshCookieOf(refresh(sessionOnly.refreshCookie())
                .andExpect(status().isOk()).andReturn());

        assertThat(rememberedCookie.getMaxAge()).isEqualTo(FOURTEEN_DAYS_SECONDS);
        assertThat(sessionCookie.getMaxAge()).isEqualTo(-1);
    }

    @Test
    void 재발급_쿠키가_없으면_401_INVALID_REFRESH_TOKEN() throws Exception {
        mockMvc.perform(post("/api/auth/refresh"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("INVALID_REFRESH_TOKEN"))
                .andExpect(jsonPath("$.path").value("/api/auth/refresh"));
    }

    @Test
    void 재발급_본문에_refreshToken을_보내도_쿠키가_없으면_401() throws Exception {
        LoginSession session = registerAndLogin(false);

        mockMvc.perform(post("/api/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(toJson(Map.of("refreshToken", session.refreshCookie().getValue()))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("INVALID_REFRESH_TOKEN"));
    }

    @Test
    void 재발급_쿠키에_access토큰을_넣으면_401_INVALID_REFRESH_TOKEN() throws Exception {
        LoginSession session = registerAndLogin(false);

        refresh(new Cookie(COOKIE_NAME, session.accessToken()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("INVALID_REFRESH_TOKEN"));
    }

    @Test
    void 재발급_위조토큰_401_INVALID_REFRESH_TOKEN() throws Exception {
        refresh(new Cookie(COOKIE_NAME, "garbage.token.value"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("INVALID_REFRESH_TOKEN"));
    }

    // ---------- logout ----------

    @Test
    void 로그아웃_후_access는_401_refresh는_재발급불가_쿠키는_삭제된다() throws Exception {
        LoginSession session = registerAndLogin(true);

        MvcResult result = logout(session, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.statusCode").value(200))
                .andExpect(jsonPath("$.data").isEmpty())
                .andExpect(jsonPath("$.error").isEmpty())
                .andReturn();

        // 만들 때와 같은 이름·경로·속성에 Max-Age=0으로 내려가야 브라우저가 쿠키를 지운다.
        MockCookie deleted = refreshCookieOf(result);
        assertThat(deleted.getValue()).isEmpty();
        assertThat(deleted.getMaxAge()).isZero();
        assertThat(deleted.getPath()).isEqualTo(COOKIE_PATH);
        assertThat(deleted.isHttpOnly()).isTrue();
        assertThat(deleted.getSecure()).isTrue();
        assertThat(deleted.getSameSite()).isEqualTo("Lax");

        assertThat(redisTemplate.keys("blacklist:*")).hasSize(1);
        assertThat(redisTemplate.keys("refresh:*")).isEmpty();

        mockMvc.perform(get("/api/users/me").header("Authorization", "Bearer " + session.accessToken()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("UNAUTHORIZED"));

        refresh(session.refreshCookie())
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("INVALID_REFRESH_TOKEN"));
    }

    @Test
    void 로그아웃_쿠키가_없어도_200_access는_블랙리스트_쿠키삭제_내려준다() throws Exception {
        LoginSession session = registerAndLogin(false);

        MvcResult result = logout(session, null, false)
                .andExpect(status().isOk())
                .andReturn();

        assertThat(refreshCookieOf(result).getMaxAge()).isZero();
        assertThat(redisTemplate.keys("blacklist:*")).hasSize(1);
        // 쿠키를 보내지 않았으니 Redis의 refresh는 그대로 남는다.
        assertThat(redisTemplate.keys("refresh:*")).hasSize(1);
    }

    @Test
    void 로그아웃_다른_사용자의_쿠키면_200이지만_그_refresh는_폐기되지_않는다() throws Exception {
        LoginSession me = registerAndLogin(false);
        LoginSession other = registerAndLogin(false);

        logout(me, other.refreshCookie()).andExpect(status().isOk());

        refresh(other.refreshCookie()).andExpect(status().isOk());
    }

    @Test
    void 로그아웃_토큰없이_호출하면_401_UNAUTHORIZED() throws Exception {
        mockMvc.perform(post("/api/auth/logout"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("UNAUTHORIZED"))
                .andExpect(jsonPath("$.path").value("/api/auth/logout"));
    }

    // ---------- CSRF(Origin 검사) ----------

    @Test
    void 재발급_허용되지_않은_Origin이면_403_FORBIDDEN_envelope_그리고_기존_쿠키는_그대로_유효() throws Exception {
        LoginSession session = registerAndLogin(false);

        mockMvc.perform(post("/api/auth/refresh")
                        .cookie(session.refreshCookie())
                        .header("Origin", EVIL_ORIGIN))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.statusCode").value(403))
                .andExpect(jsonPath("$.error").value("FORBIDDEN"))
                .andExpect(jsonPath("$.path").value("/api/auth/refresh"))
                .andExpect(header().doesNotExist("Set-Cookie"));

        // 막힌 요청은 rotation이 일어나지 않았으므로 같은 쿠키로 정상 요청이 된다.
        refresh(session.refreshCookie()).andExpect(status().isOk());
    }

    @Test
    void 재발급_허용된_Origin이면_통과하고_CORS_헤더가_붙는다() throws Exception {
        LoginSession session = registerAndLogin(false);

        mockMvc.perform(post("/api/auth/refresh")
                        .cookie(session.refreshCookie())
                        .header("Origin", ALLOWED_ORIGIN))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", ALLOWED_ORIGIN))
                .andExpect(header().string("Access-Control-Allow-Credentials", "true"));
    }

    @Test
    void 재발급_로컬_Swagger_UI_Origin은_기본으로_허용된다() throws Exception {
        LoginSession session = registerAndLogin(false);

        mockMvc.perform(post("/api/auth/refresh")
                        .cookie(session.refreshCookie())
                        .header("Origin", "http://localhost:8080"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:8080"));
    }

    @Test
    void 로그아웃_허용되지_않은_Origin이면_403이고_access도_블랙리스트에_안_올라간다() throws Exception {
        LoginSession session = registerAndLogin(false);

        mockMvc.perform(post("/api/auth/logout")
                        .header("Authorization", "Bearer " + session.accessToken())
                        .cookie(session.refreshCookie())
                        .header("Origin", EVIL_ORIGIN))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("FORBIDDEN"));

        assertThat(redisTemplate.keys("blacklist:*")).isEmpty();
        mockMvc.perform(get("/api/users/me").header("Authorization", "Bearer " + session.accessToken()))
                .andExpect(status().isOk());
    }

    // ---------- CORS ----------

    @Test
    void CORS_preflight_허용된_Origin은_credentials와_함께_통과한다() throws Exception {
        mockMvc.perform(options("/api/auth/refresh")
                        .header("Origin", ALLOWED_ORIGIN)
                        .header("Access-Control-Request-Method", "POST")
                        .header("Access-Control-Request-Headers", "content-type"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", ALLOWED_ORIGIN))
                .andExpect(header().string("Access-Control-Allow-Credentials", "true"))
                .andExpect(header().string("Access-Control-Allow-Methods", containsString("POST")));
    }

    @Test
    void CORS_preflight_허용되지_않은_Origin은_403() throws Exception {
        mockMvc.perform(options("/api/auth/login")
                        .header("Origin", EVIL_ORIGIN)
                        .header("Access-Control-Request-Method", "POST"))
                .andExpect(status().isForbidden());
    }

    @Test
    void CORS_허용되지_않은_Origin의_일반_요청도_403() throws Exception {
        register(registerBody("user@ove.com", "오브"));

        mockMvc.perform(post("/api/auth/login")
                        .header("Origin", EVIL_ORIGIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(toJson(Map.of("email", "user@ove.com", "password", "password123"))))
                .andExpect(status().isForbidden());
    }

    // ---------- helpers ----------

    private record LoginSession(String accessToken, Cookie refreshCookie) {
    }

    private ResultActions register(Map<String, Object> body) throws Exception {
        return mockMvc.perform(post("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(toJson(body)));
    }

    // rememberMe가 null이면 요청 본문에서 필드를 아예 뺀다.
    private ResultActions login(String email, String password, Boolean rememberMe) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("email", email);
        body.put("password", password);
        if (rememberMe != null) {
            body.put("rememberMe", rememberMe);
        }
        return mockMvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(toJson(body)));
    }

    private ResultActions refresh(Cookie refreshCookie) throws Exception {
        return mockMvc.perform(post("/api/auth/refresh").cookie(refreshCookie));
    }

    private ResultActions logout(LoginSession session, Cookie cookieToSend) throws Exception {
        return logout(session, cookieToSend == null ? session.refreshCookie() : cookieToSend, true);
    }

    private ResultActions logout(LoginSession session, Cookie cookieToSend, boolean sendCookie) throws Exception {
        var request = post("/api/auth/logout").header("Authorization", "Bearer " + session.accessToken());
        if (sendCookie) {
            request.cookie(cookieToSend);
        }
        return mockMvc.perform(request);
    }

    private LoginSession registerAndLogin(boolean rememberMe) throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String email = suffix + "@ove.com";
        register(registerBody(email, "닉" + suffix.substring(0, 6)));

        MvcResult result = login(email, "password123", rememberMe).andExpect(status().isOk()).andReturn();
        JsonNode data = objectMapper.readTree(result.getResponse().getContentAsString()).get("data");
        MockCookie cookie = refreshCookieOf(result);
        return new LoginSession(data.get("accessToken").asString(), new Cookie(COOKIE_NAME, cookie.getValue()));
    }

    // 응답의 Set-Cookie를 파싱한 refreshToken 쿠키 (속성 검증용)
    private MockCookie refreshCookieOf(MvcResult result) {
        Cookie cookie = result.getResponse().getCookie(COOKIE_NAME);
        assertThat(cookie).as("Set-Cookie: %s", COOKIE_NAME).isInstanceOf(MockCookie.class);
        return (MockCookie) cookie;
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
