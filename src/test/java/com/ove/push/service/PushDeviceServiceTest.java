package com.ove.push.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.times;

import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;

import com.ove.global.exception.BusinessException;
import com.ove.global.exception.ErrorCode;
import com.ove.push.domain.DevicePlatform;
import com.ove.push.domain.PushDevice;
import com.ove.push.dto.PushDeviceRequest;
import com.ove.push.dto.PushDeviceResponse;
import com.ove.push.repository.PushDeviceRepository;
import com.ove.user.domain.User;
import com.ove.user.repository.UserRepository;

@ExtendWith(MockitoExtension.class)
class PushDeviceServiceTest {

    @Mock
    private PushDeviceRepository pushDeviceRepository;

    @Mock
    private UserRepository userRepository;

    private PushDeviceService pushDeviceService;

    private User user;

    @BeforeEach
    void setUp() {
        pushDeviceService = new PushDeviceService(pushDeviceRepository, userRepository);
        user = User.builder().email("push@ove.com").password("hashed").nickname("푸시").build();
        ReflectionTestUtils.setField(user, "id", UUID.randomUUID());
    }

    @Test
    @DisplayName("새 토큰은 기기를 등록하고, 같은 사용자의 토큰은 설정만 갱신한다")
    void registerAndUpdate() {
        given(userRepository.findById(user.getId())).willReturn(Optional.of(user));
        given(pushDeviceRepository.findByPushToken("token-1")).willReturn(Optional.empty());
        given(pushDeviceRepository.saveAndFlush(any(PushDevice.class))).willAnswer(invocation -> {
            PushDevice device = invocation.getArgument(0);
            ReflectionTestUtils.setField(device, "id", 1L);
            return device;
        });

        PushDeviceResponse created = pushDeviceService.register(user.getId(),
                request(DevicePlatform.ANDROID, "token-1", true));

        assertThat(created.getDeviceId()).isEqualTo(1L);
        assertThat(created.getPlatform()).isEqualTo(DevicePlatform.ANDROID);
        assertThat(created.isAlertEnabled()).isTrue();

        PushDevice existing = PushDevice.builder()
                .user(user)
                .platform(DevicePlatform.ANDROID)
                .pushToken("token-1")
                .alertEnabled(true)
                .build();
        ReflectionTestUtils.setField(existing, "id", 2L);
        given(pushDeviceRepository.findByPushToken("token-1")).willReturn(Optional.of(existing));

        PushDeviceResponse updated = pushDeviceService.register(user.getId(),
                request(DevicePlatform.IOS, "token-1", false));

        assertThat(updated.getDeviceId()).isEqualTo(2L);
        assertThat(updated.getPlatform()).isEqualTo(DevicePlatform.IOS);
        assertThat(updated.isAlertEnabled()).isFalse();
        then(pushDeviceRepository).should(times(2)).findByPushToken("token-1");
    }

    @Test
    @DisplayName("다른 사용자가 등록한 토큰은 409, 없는 사용자는 404")
    void conflicts() {
        User other = User.builder().email("other@ove.com").password("hashed").nickname("다른").build();
        ReflectionTestUtils.setField(other, "id", UUID.randomUUID());
        PushDevice existing = PushDevice.builder()
                .user(other)
                .platform(DevicePlatform.ANDROID)
                .pushToken("token-1")
                .alertEnabled(true)
                .build();

        given(userRepository.findById(user.getId())).willReturn(Optional.of(user));
        given(pushDeviceRepository.findByPushToken("token-1")).willReturn(Optional.of(existing));

        assertThatThrownBy(() -> pushDeviceService.register(user.getId(),
                request(DevicePlatform.ANDROID, "token-1", true)))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.PUSH_TOKEN_ALREADY_REGISTERED));

        given(userRepository.findById(user.getId())).willReturn(Optional.empty());
        assertThatThrownBy(() -> pushDeviceService.register(user.getId(),
                request(DevicePlatform.ANDROID, "token-2", true)))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.USER_NOT_FOUND));
    }

    @Test
    @DisplayName("동시 등록으로 유일 제약에 걸리면 409로 바꾼다")
    void raceCondition() {
        given(userRepository.findById(user.getId())).willReturn(Optional.of(user));
        given(pushDeviceRepository.findByPushToken("token-1")).willReturn(Optional.empty());
        given(pushDeviceRepository.saveAndFlush(any(PushDevice.class)))
                .willThrow(new DataIntegrityViolationException("duplicate"));

        assertThatThrownBy(() -> pushDeviceService.register(user.getId(),
                request(DevicePlatform.ANDROID, "token-1", true)))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.PUSH_TOKEN_ALREADY_REGISTERED));
    }

    private PushDeviceRequest request(DevicePlatform platform, String pushToken, boolean alertEnabled) {
        PushDeviceRequest request = new PushDeviceRequest();
        ReflectionTestUtils.setField(request, "platform", platform);
        ReflectionTestUtils.setField(request, "pushToken", pushToken);
        ReflectionTestUtils.setField(request, "alertEnabled", alertEnabled);
        return request;
    }
}
