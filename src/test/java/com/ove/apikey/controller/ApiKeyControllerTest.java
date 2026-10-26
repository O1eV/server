package com.ove.apikey.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
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
import org.springframework.test.web.servlet.ResultActions;

import com.ove.apikey.domain.ApiKey;
import com.ove.global.security.JwtProperties;
import com.ove.global.security.JwtProvider;
import com.ove.support.IntegrationTestSupport;
import com.ove.user.domain.User;

import tools.jackson.databind.JsonNode;

/**
 * /api/keys Controller·Security 통합 테스트 (H2, Redis Testcontainers).
 */
class ApiKeyControllerTest extends IntegrationTestSupport {

    @Autowired
    private JwtProvider jwtProvider;

    @Autowired
    private JwtProperties jwtProperties;

    @Test
    @DisplayName("토큰 없이 3개 API 모두 401 UNAUTHORIZED, 만료 토큰은 TOKEN_EXPIRED")
    void unauthorized() throws Exception {
        mockMvc.perform(post("/api/keys").contentType(MediaType.APPLICATION_JSON).content(toJson(Map.of("name", "키"))))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.error").value("UNAUTHORIZED"));
        mockMvc.perform(get("/api/keys"))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.error").value("UNAUTHORIZED"));
        mockMvc.perform(delete("/api/keys/" + UUID.randomUUID()))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.error").value("UNAUTHORIZED"));

        JwtProvider expiredProvider = new JwtProvider(new JwtProperties(jwtProperties.secret(),
                Duration.ofSeconds(-10), Duration.ofDays(14)));
        String expired = expiredProvider.createAccessToken(UUID.randomUUID()).token();
        mockMvc.perform(get("/api/keys").header("Authorization", "Bearer " + expired))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.error").value("TOKEN_EXPIRED"));
    }

    @Test
    @DisplayName("발급 201: envelope, 원문 ove_+hex64, prefix 앞 8자리, DB에는 원문이 없고 SHA-256 해시만 있다")
    void createDoesNotStorePlaintext() throws Exception {
        User user = createUser("a@ove.com", "에이");

        JsonNode data = create(token(user), "  내 노트북  ")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.statusCode").value(201))
                .andExpect(jsonPath("$.path").value("/api/keys"))
                .andExpect(jsonPath("$.message").value("API 키가 발급되었습니다."))
                .andExpect(jsonPath("$.error").isEmpty())
                .andExpect(jsonPath("$.data.keyId").isString())
                .andExpect(jsonPath("$.data.name").value("내 노트북"))
                .andExpect(jsonPath("$.data.createdAt").isString())
                .andReturn().getResponse().getContentAsString().transform(this::dataOf);

        String rawKey = data.get("apiKey").asString();
        assertThat(rawKey).matches("ove_[0-9a-f]{64}");
        assertThat(data.get("prefix").asString()).isEqualTo(rawKey.substring(0, 8));

        List<ApiKey> stored = apiKeyRepository.findAll();
        assertThat(stored).hasSize(1);
        ApiKey key = stored.get(0);
        assertThat(key.getUserId()).isEqualTo(user.getId());
        assertThat(key.getPrefix()).isEqualTo(rawKey.substring(0, 8));
        assertThat(key.getKeyHash()).matches("[0-9a-f]{64}").isNotEqualTo(rawKey).doesNotContain(rawKey);
        assertThat(key.getKeyHash()).isEqualTo(sha256(rawKey));
        assertThat(key.getLastUsedAt()).isNull();
        assertThat(key.getRevokedAt()).isNull();
        // 원문이 어떤 컬럼에도 없다
        assertThat(key.getName()).doesNotContain(rawKey);
    }

    @Test
    @DisplayName("발급 400: name이 없거나 공백뿐이거나 31자 이상")
    void createValidation() throws Exception {
        String token = token(createUser("a@ove.com", "에이"));

        for (String body : new String[] {"{}", "{\"name\":\"   \"}", "{\"name\":\"" + "a".repeat(31) + "\"}",
                "{\"name\":\"  " + "a".repeat(31) + "  \"}", "not json"}) {
            mockMvc.perform(post("/api/keys").header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("INVALID_REQUEST"))
                    .andExpect(jsonPath("$.data").isEmpty());
        }
        assertThat(apiKeyRepository.count()).isZero();
        create(token, "a".repeat(30)).andExpect(status().isCreated()); // 정확히 30자는 통과
    }

    @Test
    @DisplayName("이름은 중복 허용, 4번째 발급은 409 API_KEY_LIMIT_EXCEEDED")
    void limitOfThree() throws Exception {
        String token = token(createUser("a@ove.com", "에이"));

        create(token, "같은 이름").andExpect(status().isCreated());
        create(token, "같은 이름").andExpect(status().isCreated());
        create(token, "같은 이름").andExpect(status().isCreated());

        create(token, "넷째")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.statusCode").value(409))
                .andExpect(jsonPath("$.error").value("API_KEY_LIMIT_EXCEEDED"))
                .andExpect(jsonPath("$.data").isEmpty());
        assertThat(apiKeyRepository.count()).isEqualTo(3);
    }

    @Test
    @DisplayName("폐기하면 목록·활성 개수에서 빠지고 새로 발급할 수 있다 (폐기 행은 소프트로 남는다)")
    void revokeFreesSlot() throws Exception {
        User user = createUser("a@ove.com", "에이");
        String token = token(user);
        String first = keyIdOf(create(token, "하나"));
        create(token, "둘").andExpect(status().isCreated());
        create(token, "셋").andExpect(status().isCreated());

        mockMvc.perform(delete("/api/keys/" + first).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("API 키가 폐기되었습니다."))
                .andExpect(jsonPath("$.data").isEmpty())
                .andExpect(jsonPath("$.error").isEmpty());

        mockMvc.perform(get("/api/keys").header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.data.items", hasSize(2)))
                .andExpect(jsonPath("$.data.items[0].name").value("둘"))
                .andExpect(jsonPath("$.data.items[1].name").value("셋"));
        assertThat(apiKeyRepository.countByUserIdAndRevokedAtIsNull(user.getId())).isEqualTo(2);
        assertThat(apiKeyRepository.count()).isEqualTo(3); // 행은 남아 있다
        assertThat(apiKeyRepository.findById(UUID.fromString(first)).orElseThrow().getRevokedAt()).isNotNull();
        mockMvc.perform(get("/api/users/me").header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.data.stats.activeApiKeyCount").value(2));

        create(token, "넷").andExpect(status().isCreated()); // 폐기한 자리만큼 다시 발급 가능
    }

    @Test
    @DisplayName("없는 키·이미 폐기한 키·남의 키 폐기는 모두 404 API_KEY_NOT_FOUND, 남의 키는 그대로 남는다")
    void revokeNotFound() throws Exception {
        String tokenA = token(createUser("a@ove.com", "에이"));
        String tokenB = token(createUser("b@ove.com", "비이"));
        String aKey = keyIdOf(create(tokenA, "에이 키"));

        // 남의 키
        mockMvc.perform(delete("/api/keys/" + aKey).header("Authorization", "Bearer " + tokenB))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("API_KEY_NOT_FOUND"));
        assertThat(apiKeyRepository.findById(UUID.fromString(aKey)).orElseThrow().getRevokedAt()).isNull();
        // 없는 키
        mockMvc.perform(delete("/api/keys/" + UUID.randomUUID()).header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.error").value("API_KEY_NOT_FOUND"));
        // 이미 폐기한 키
        mockMvc.perform(delete("/api/keys/" + aKey).header("Authorization", "Bearer " + tokenA)).andExpect(status().isOk());
        mockMvc.perform(delete("/api/keys/" + aKey).header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.error").value("API_KEY_NOT_FOUND"));
        // UUID 형식이 아니면 400
        mockMvc.perform(delete("/api/keys/not-a-uuid").header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("INVALID_REQUEST"));
    }

    @Test
    @DisplayName("목록: 내 활성 키만, 원문·해시 없이 keyId·name·prefix·createdAt·lastUsedAt(null)")
    void listOwnActiveOnly() throws Exception {
        String tokenA = token(createUser("a@ove.com", "에이"));
        String tokenB = token(createUser("b@ove.com", "비이"));
        create(tokenA, "에이 키");
        create(tokenB, "비이 키");

        String body = mockMvc.perform(get("/api/keys").header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.statusCode").value(200))
                .andExpect(jsonPath("$.path").value("/api/keys"))
                .andExpect(jsonPath("$.message").value("요청에 성공했습니다."))
                .andExpect(jsonPath("$.data.items", hasSize(1)))
                .andExpect(jsonPath("$.data.items[0].name").value("에이 키"))
                .andExpect(jsonPath("$.data.items[0].keyId").isString())
                .andExpect(jsonPath("$.data.items[0].prefix").value(org.hamcrest.Matchers.startsWith("ove_")))
                .andExpect(jsonPath("$.data.items[0].createdAt").isString())
                .andExpect(jsonPath("$.data.items[0].lastUsedAt").isEmpty())
                .andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain("apiKey").doesNotContain("keyHash");

        mockMvc.perform(get("/api/keys").header("Authorization", "Bearer " + token(createUser("c@ove.com", "씨이"))))
                .andExpect(jsonPath("$.data.items", hasSize(0)));
    }

    @Test
    @DisplayName("같은 회원이 동시에 여러 번 발급해도 활성 키는 3개를 넘지 않는다")
    void concurrentCreateNeverExceedsLimit() throws Exception {
        User user = createUser("a@ove.com", "에이");
        String token = token(user);
        int threads = 8;

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Integer> statuses = new ArrayList<>();
        try {
            CountDownLatch ready = new CountDownLatch(threads);
            CountDownLatch go = new CountDownLatch(1);
            List<Future<Integer>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                String name = "키" + i;
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    return create(token, name).andReturn().getResponse().getStatus();
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

        assertThat(statuses.stream().filter(s -> s == 201).count()).isEqualTo(3);
        assertThat(statuses.stream().filter(s -> s == 409).count()).isEqualTo(threads - 3);
        assertThat(apiKeyRepository.countByUserIdAndRevokedAtIsNull(user.getId())).isEqualTo(3);
    }

    @Test
    @DisplayName("같은 키를 동시에 폐기해도 500 없이 1건만 200, 나머지는 404")
    void concurrentRevoke() throws Exception {
        String token = token(createUser("a@ove.com", "에이"));
        String keyId = keyIdOf(create(token, "키"));
        int threads = 6;

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Integer> statuses = new ArrayList<>();
        try {
            CountDownLatch ready = new CountDownLatch(threads);
            CountDownLatch go = new CountDownLatch(1);
            List<Future<Integer>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    return mockMvc.perform(delete("/api/keys/" + keyId).header("Authorization", "Bearer " + token))
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

        assertThat(statuses.stream().filter(s -> s == 200).count()).isEqualTo(1);
        assertThat(statuses.stream().filter(s -> s == 404).count()).isEqualTo(threads - 1);
    }

    @Test
    @DisplayName("/users/me: stats.activeApiKeyCount는 내 활성 키 수이고 다른 회원 것은 세지 않는다")
    void myInfoActiveApiKeyCount() throws Exception {
        String tokenA = token(createUser("a@ove.com", "에이"));
        String tokenB = token(createUser("b@ove.com", "비이"));
        create(tokenA, "하나");
        create(tokenA, "둘");
        create(tokenB, "셋");

        mockMvc.perform(get("/api/users/me").header("Authorization", "Bearer " + tokenA))
                .andExpect(jsonPath("$.data.stats.activeApiKeyCount").value(2));
        mockMvc.perform(get("/api/users/me").header("Authorization", "Bearer " + tokenB))
                .andExpect(jsonPath("$.data.stats.activeApiKeyCount").value(1));
    }

    // ---------- helpers ----------

    private ResultActions create(String token, String name) throws Exception {
        return mockMvc.perform(post("/api/keys").header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content(toJson(Map.of("name", name))));
    }

    private String keyIdOf(ResultActions result) throws Exception {
        return result.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()
                .transform(this::dataOf).get("keyId").asString();
    }

    private JsonNode dataOf(String json) {
        return objectMapper.readTree(json).get("data");
    }

    private User createUser(String email, String nickname) {
        return userRepository.save(User.builder().email(email)
                .password("$2a$10$abcdefghijklmnopqrstuuabcdefghijklmnopqrstuvwxyz01234").nickname(nickname).build());
    }

    private String token(User user) {
        return jwtProvider.createAccessToken(user.getId()).token();
    }

    private static String sha256(String value) throws Exception {
        return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }
}
