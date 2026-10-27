package com.ove.user.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockCookie;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

import com.ove.global.security.JwtProvider;
import com.ove.support.IntegrationTestSupport;
import com.ove.user.domain.User;
import com.ove.user.domain.UserStatus;
import com.ove.watchlist.domain.Watchlist;

import jakarta.servlet.http.Cookie;

/**
 * DELETE /api/users/me 통합 테스트(실제 Redis, H2). 탈퇴 뒤 로그인·재발급·토큰이 모두 막히는지와 데이터 처리를 확인한다.
 */
class UserWithdrawControllerTest extends IntegrationTestSupport {

    private static final String PASSWORD = "password123";

    @Autowired
    private JwtProvider jwtProvider;

    private record Session(String accessToken, Cookie refreshCookie) {
    }

    @Test
    @DisplayName("정상 탈퇴: 200, 쿠키 삭제, 모든 기기의 refresh 삭제, access 블랙리스트, 개인정보 익명화, 모의계좌 보존, 약관 이력 보존")
    void withdrawsSuccessfully() throws Exception {
        register("user@ove.com", "오브");
        UUID userId = userRepository.findByEmail("user@ove.com").orElseThrow().getId();
        Session phone = login("user@ove.com");
        Session laptop = login("user@ove.com"); // 다른 기기
        assertThat(redisTemplate.keys("refresh:" + userId + ":*")).hasSize(2);
        assertThat(paperAccountRepository.findByUserId(userId)).isPresent();

        MvcResult result = withdraw(phone.accessToken(), PASSWORD)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.statusCode").value(200))
                .andExpect(jsonPath("$.path").value("/api/users/me"))
                .andExpect(jsonPath("$.data").isEmpty())
                .andExpect(jsonPath("$.error").isEmpty())
                .andReturn();

        // refresh 쿠키는 만들 때와 같은 이름·경로·속성에 Max-Age=0으로 지운다(로그아웃과 같은 방식)
        MockCookie deleted = (MockCookie) result.getResponse().getCookie("refreshToken");
        assertThat(deleted).isNotNull();
        assertThat(deleted.getValue()).isEmpty();
        assertThat(deleted.getMaxAge()).isZero();
        assertThat(deleted.getPath()).isEqualTo("/api/auth");
        assertThat(deleted.isHttpOnly()).isTrue();
        assertThat(deleted.getSecure()).isTrue();
        assertThat(deleted.getSameSite()).isEqualTo("Lax");

        // Redis: 모든 기기의 refresh가 사라지고, 지금 쓰던 access는 남은 만료 시간만큼 블랙리스트에 올라간다
        assertThat(redisTemplate.keys("refresh:" + userId + ":*")).isEmpty();
        String jti = jwtProvider.parse(phone.accessToken()).getId();
        assertThat(redisTemplate.hasKey("blacklist:" + jti)).isTrue();
        assertThat(redisTemplate.getExpire("blacklist:" + jti)).isBetween(1L, 1800L);
        // 다른 기기의 access를 막는 탈퇴 표시: TTL은 access 최대 수명(30분)
        assertThat(redisTemplate.getExpire("withdrawn:" + userId)).isBetween(1700L, 1800L);

        // DB: soft delete + 개인정보 익명화
        User user = userRepository.findById(userId).orElseThrow();
        assertThat(user.getStatus()).isEqualTo(UserStatus.WITHDRAWN);
        assertThat(user.getDeletedAt()).isNotNull();
        assertThat(user.getEmail()).isEqualTo("withdrawn+" + userId + "@deleted.invalid");
        assertThat(user.getNickname()).startsWith("탈퇴").isNotEqualTo("오브");
        assertThat(user.getPassword()).isEqualTo("!withdrawn");
        // 모의계좌는 개인정보가 없고 #19의 주문·보유가 참조하므로 지우지 않는다
        assertThat(paperAccountRepository.findByUserId(userId)).isPresent();
        // 약관 동의 이력은 지우지 않고 user_id로 남는다
        assertThat(termsAgreementRepository.findAllByUserId(userId)).hasSize(3);

        // 탈퇴 직후: 방금 쓴 access는 블랙리스트로 401, 두 기기의 refresh는 재발급 불가, 같은 계정으로 로그인 불가
        mockMvc.perform(get("/api/users/me").header("Authorization", "Bearer " + phone.accessToken()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("UNAUTHORIZED"));
        for (Session session : new Session[] {phone, laptop}) {
            mockMvc.perform(post("/api/auth/refresh").cookie(session.refreshCookie()))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.error").value("INVALID_REFRESH_TOKEN"));
        }
        loginRequest("user@ove.com", PASSWORD)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("INVALID_CREDENTIALS"));

        // 다른 기기에서 발급된 access도 탈퇴 직후부터 필터에서 401로 막힌다 (어떤 인증 API든 마찬가지)
        mockMvc.perform(get("/api/users/me").header("Authorization", "Bearer " + laptop.accessToken()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("UNAUTHORIZED"));
        withdraw(laptop.accessToken(), PASSWORD)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("UNAUTHORIZED"));
        mockMvc.perform(post("/api/auth/logout").header("Authorization", "Bearer " + laptop.accessToken())
                        .header("Origin", "http://localhost:5173"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("UNAUTHORIZED"));
    }

    @Test
    @DisplayName("같은 회원이 동시에 여러 번 탈퇴해도 500 없이 1건만 200, 나머지는 404")
    void concurrentWithdrawalsDoNotFail() throws Exception {
        register("user@ove.com", "오브");
        UUID userId = userRepository.findByEmail("user@ove.com").orElseThrow().getId();
        int threads = 4;
        List<String> tokens = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            tokens.add(login("user@ove.com").accessToken());
        }

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Integer> statuses = new ArrayList<>();
        try {
            CountDownLatch ready = new CountDownLatch(threads);
            CountDownLatch go = new CountDownLatch(1);
            List<Future<Integer>> futures = new ArrayList<>();
            for (String token : tokens) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    return withdraw(token, PASSWORD).andReturn().getResponse().getStatus();
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

        assertThat(statuses.stream().filter(s -> s == 200).count()).isEqualTo(1);
        assertThat(statuses.stream().filter(s -> s == 404).count()).isEqualTo(threads - 1);
        assertThat(userRepository.findById(userId).orElseThrow().getStatus()).isEqualTo(UserStatus.WITHDRAWN);
    }

    @Test
    @DisplayName("탈퇴하면 내 관심종목만 지워지고, 비밀번호가 틀리면 그대로 남는다")
    void withdrawalDeletesOwnWatchlistOnly() throws Exception {
        register("a@ove.com", "에이회원");
        register("b@ove.com", "비회원");
        UUID aId = userRepository.findByEmail("a@ove.com").orElseThrow().getId();
        UUID bId = userRepository.findByEmail("b@ove.com").orElseThrow().getId();
        watchlistRepository.save(Watchlist.builder().userId(aId).symbol("KRW-BTC").build());
        watchlistRepository.save(Watchlist.builder().userId(aId).symbol("KRW-ETH").build());
        watchlistRepository.save(Watchlist.builder().userId(bId).symbol("KRW-BTC").build());
        Session a = login("a@ove.com");

        withdraw(a.accessToken(), "wrong-password1").andExpect(status().isUnauthorized());
        assertThat(watchlistRepository.countByUserId(aId)).isEqualTo(2);

        withdraw(a.accessToken(), PASSWORD).andExpect(status().isOk());

        assertThat(watchlistRepository.countByUserId(aId)).isZero();
        assertThat(watchlistRepository.countByUserId(bId)).isEqualTo(1);
    }

    @Test
    @DisplayName("탈퇴한 뒤 같은 이메일·닉네임으로 다시 가입하고 로그인할 수 있다")
    void canReRegisterWithSameEmailAndNickname() throws Exception {
        register("user@ove.com", "오브");
        Session session = login("user@ove.com");
        withdraw(session.accessToken(), PASSWORD).andExpect(status().isOk());

        register("user@ove.com", "오브");

        loginRequest("user@ove.com", PASSWORD).andExpect(status().isOk());
        assertThat(userRepository.findAll()).hasSize(2); // 탈퇴한 행(익명화)과 새 회원
    }

    @Test
    @DisplayName("이미 탈퇴한 회원이 아직 유효한 다른 access로 다시 요청하면 필터에서 401 (서비스까지 가지 않는다)")
    void secondWithdrawalIsUnauthorized() throws Exception {
        register("user@ove.com", "오브");
        Session phone = login("user@ove.com");
        Session laptop = login("user@ove.com");
        withdraw(phone.accessToken(), PASSWORD).andExpect(status().isOk());

        withdraw(laptop.accessToken(), PASSWORD)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.statusCode").value(401))
                .andExpect(jsonPath("$.error").value("UNAUTHORIZED"))
                .andExpect(jsonPath("$.data").isEmpty());
    }

    @Test
    @DisplayName("비밀번호가 틀리면 401 INVALID_CREDENTIALS이고 아무것도 바뀌지 않는다 (쿠키·Redis·DB 그대로)")
    void wrongPasswordChangesNothing() throws Exception {
        register("user@ove.com", "오브");
        UUID userId = userRepository.findByEmail("user@ove.com").orElseThrow().getId();
        Session session = login("user@ove.com");

        withdraw(session.accessToken(), "wrong-password1")
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("INVALID_CREDENTIALS"))
                .andExpect(jsonPath("$.message").value("비밀번호가 일치하지 않습니다."))
                .andExpect(jsonPath("$.data").isEmpty())
                .andExpect(header().doesNotExist("Set-Cookie"));

        User user = userRepository.findById(userId).orElseThrow();
        assertThat(user.getStatus()).isEqualTo(UserStatus.ACTIVE);
        assertThat(user.getEmail()).isEqualTo("user@ove.com");
        assertThat(user.getDeletedAt()).isNull();
        assertThat(paperAccountRepository.findByUserId(userId)).isPresent();
        assertThat(redisTemplate.keys("refresh:" + userId + ":*")).hasSize(1);
        assertThat(redisTemplate.keys("blacklist:*")).isEmpty();
        mockMvc.perform(get("/api/users/me").header("Authorization", "Bearer " + session.accessToken()))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("비밀번호가 없거나 비어 있거나 본문이 없으면 400 INVALID_REQUEST")
    void missingPasswordIsBadRequest() throws Exception {
        register("user@ove.com", "오브");
        Session session = login("user@ove.com");
        String bearer = "Bearer " + session.accessToken();

        mockMvc.perform(delete("/api/users/me").header("Authorization", bearer)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_REQUEST"));
        mockMvc.perform(delete("/api/users/me").header("Authorization", bearer)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"password\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_REQUEST"));
        mockMvc.perform(delete("/api/users/me").header("Authorization", bearer))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_REQUEST"));

        assertThat(userRepository.findByEmail("user@ove.com").orElseThrow().getStatus()).isEqualTo(UserStatus.ACTIVE);
    }

    @Test
    @DisplayName("Bearer 토큰이 없거나 잘못되면 401")
    void requiresBearer() throws Exception {
        mockMvc.perform(delete("/api/users/me").contentType(MediaType.APPLICATION_JSON)
                        .content(toJson(Map.of("password", PASSWORD))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("UNAUTHORIZED"))
                .andExpect(jsonPath("$.path").value("/api/users/me"));
        mockMvc.perform(delete("/api/users/me").header("Authorization", "Bearer forged.token.value")
                        .contentType(MediaType.APPLICATION_JSON).content(toJson(Map.of("password", PASSWORD))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("UNAUTHORIZED"));
    }

    @Test
    @DisplayName("한 회원이 탈퇴해도 다른 회원의 계정·refresh·모의계좌는 그대로다")
    void otherUsersAreUnaffected() throws Exception {
        register("a@ove.com", "에이회원");
        register("b@ove.com", "비회원");
        UUID bId = userRepository.findByEmail("b@ove.com").orElseThrow().getId();
        Session a = login("a@ove.com");
        Session b = login("b@ove.com");

        withdraw(a.accessToken(), PASSWORD).andExpect(status().isOk());

        assertThat(userRepository.findById(bId).orElseThrow().getStatus()).isEqualTo(UserStatus.ACTIVE);
        assertThat(redisTemplate.keys("refresh:" + bId + ":*")).hasSize(1);
        assertThat(paperAccountRepository.findByUserId(bId)).isPresent();
        mockMvc.perform(get("/api/users/me").header("Authorization", "Bearer " + b.accessToken()))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/auth/refresh").cookie(b.refreshCookie())).andExpect(status().isOk());
    }

    // ---------- helpers ----------

    private ResultActions withdraw(String accessToken, String password) throws Exception {
        return mockMvc.perform(delete("/api/users/me")
                .header("Authorization", "Bearer " + accessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(toJson(Map.of("password", password))));
    }

    private void register(String email, String nickname) throws Exception {
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(toJson(Map.of(
                                "email", email,
                                "password", PASSWORD,
                                "passwordConfirm", PASSWORD,
                                "nickname", nickname,
                                "termsAgreed", true,
                                "privacyAgreed", true,
                                "investmentNoticeAgreed", true))))
                .andExpect(status().isCreated());
    }

    private ResultActions loginRequest(String email, String password) throws Exception {
        return mockMvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(toJson(Map.of("email", email, "password", password))));
    }

    private Session login(String email) throws Exception {
        MvcResult result = loginRequest(email, PASSWORD).andExpect(status().isOk()).andReturn();
        String accessToken = objectMapper.readTree(result.getResponse().getContentAsString())
                .get("data").get("accessToken").asString();
        MockCookie cookie = (MockCookie) result.getResponse().getCookie("refreshToken");
        return new Session(accessToken, new Cookie("refreshToken", cookie.getValue()));
    }
}
