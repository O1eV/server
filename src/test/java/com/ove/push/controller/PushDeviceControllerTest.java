package com.ove.push.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.ove.auth.repository.TokenRedisRepository;
import com.ove.global.security.JwtProvider;
import com.ove.push.domain.DevicePlatform;
import com.ove.push.repository.PushDeviceRepository;
import com.ove.user.domain.User;
import com.ove.user.repository.UserRepository;

import tools.jackson.databind.ObjectMapper;

/**
 * 기기 등록 API의 인증·검증·envelope를 확인한다.
 * Redis는 JWT 필터가 참조하는 토큰 저장소만 목으로 바꿔 Docker 없이 돌린다.
 */
@SpringBootTest
@AutoConfigureMockMvc
class PushDeviceControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JwtProvider jwtProvider;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PushDeviceRepository pushDeviceRepository;

    @MockitoBean
    private TokenRedisRepository tokenRedisRepository;

    @AfterEach
    void tearDown() {
        pushDeviceRepository.deleteAllInBatch();
        userRepository.deleteAllInBatch();
    }

    @Test
    @DisplayName("토큰이 없으면 401 UNAUTHORIZED")
    void unauthorized() throws Exception {
        mockMvc.perform(post("/api/devices").contentType(MediaType.APPLICATION_JSON)
                        .content(request(DevicePlatform.ANDROID, "token-1", true)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("UNAUTHORIZED"));
    }

    @Test
    @DisplayName("새 토큰 등록 200, 같은 토큰 재등록은 기기 하나를 갱신한다")
    void registerAndUpdate() throws Exception {
        String token = tokenOf("push@ove.com", "푸시");

        mockMvc.perform(post("/api/devices").header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request(DevicePlatform.ANDROID, "token-1", true)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.statusCode").value(200))
                .andExpect(jsonPath("$.path").value("/api/devices"))
                .andExpect(jsonPath("$.data.deviceId").isNumber())
                .andExpect(jsonPath("$.data.platform").value("ANDROID"))
                .andExpect(jsonPath("$.data.alertEnabled").value(true))
                .andExpect(jsonPath("$.error").isEmpty());

        mockMvc.perform(post("/api/devices").header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request(DevicePlatform.IOS, " token-1 ", false)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.platform").value("IOS"))
                .andExpect(jsonPath("$.data.alertEnabled").value(false));
    }

    @Test
    @DisplayName("다른 사용자의 토큰은 409, 없는 사용자는 404, 잘못된 본문은 400")
    void errors() throws Exception {
        String firstToken = tokenOf("first@ove.com", "첫번째");
        String secondToken = tokenOf("second@ove.com", "두번째");
        register(firstToken, "token-1");

        mockMvc.perform(post("/api/devices").header("Authorization", bearer(secondToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request(DevicePlatform.ANDROID, "token-1", true)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("PUSH_TOKEN_ALREADY_REGISTERED"));

        String missingUserToken = jwtProvider.createAccessToken(UUID.randomUUID()).token();
        mockMvc.perform(post("/api/devices").header("Authorization", bearer(missingUserToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request(DevicePlatform.ANDROID, "token-2", true)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("USER_NOT_FOUND"));

        mockMvc.perform(post("/api/devices").header("Authorization", bearer(secondToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("platform", "ANDROID", "pushToken", " "))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_REQUEST"));
    }

    private void register(String accessToken, String pushToken) throws Exception {
        mockMvc.perform(post("/api/devices").header("Authorization", bearer(accessToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request(DevicePlatform.ANDROID, pushToken, true)))
                .andExpect(status().isOk());
    }

    private String tokenOf(String email, String nickname) {
        User user = userRepository.save(User.builder()
                .email(email)
                .password("hashed-password")
                .nickname(nickname)
                .build());
        return jwtProvider.createAccessToken(user.getId()).token();
    }

    private String request(DevicePlatform platform, String pushToken, boolean alertEnabled) throws Exception {
        return objectMapper.writeValueAsString(Map.of(
                "platform", platform.name(),
                "pushToken", pushToken,
                "alertEnabled", alertEnabled));
    }

    private String bearer(String token) {
        return "Bearer " + token;
    }
}
