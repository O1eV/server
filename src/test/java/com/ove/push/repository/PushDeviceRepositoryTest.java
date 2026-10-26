package com.ove.push.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;

import com.ove.push.domain.DevicePlatform;
import com.ove.push.domain.PushDevice;
import com.ove.user.domain.User;
import com.ove.user.repository.UserRepository;

@DataJpaTest
class PushDeviceRepositoryTest {

    @Autowired
    private PushDeviceRepository pushDeviceRepository;

    @Autowired
    private UserRepository userRepository;

    @AfterEach
    void tearDown() {
        pushDeviceRepository.deleteAllInBatch();
        userRepository.deleteAllInBatch();
    }

    @Test
    @DisplayName("관심 종목 사용자 중 알림을 켠 기기만 찾는다")
    void findsEnabledDevicesOfUsers() {
        User first = userRepository.save(user("first@ove.com", "첫번째"));
        User second = userRepository.save(user("second@ove.com", "두번째"));
        User other = userRepository.save(user("other@ove.com", "다른사람"));
        PushDevice enabled = pushDeviceRepository.save(device(first, "token-1", true));
        pushDeviceRepository.save(device(first, "token-2", false));
        PushDevice secondEnabled = pushDeviceRepository.save(device(second, "token-3", true));
        pushDeviceRepository.save(device(other, "token-4", true));

        List<PushDevice> devices = pushDeviceRepository.findAllByUserIdInAndAlertEnabledTrue(
                List.of(first.getId(), second.getId()));

        assertThat(devices).extracting(PushDevice::getPushToken)
                .containsExactlyInAnyOrder("token-1", "token-3");
        assertThat(devices).extracting(PushDevice::getId)
                .containsExactlyInAnyOrder(enabled.getId(), secondEnabled.getId());
    }

    @Test
    @DisplayName("회원 탈퇴 시 그 회원의 기기만 지운다")
    void deletesOnlyOwnDevices() {
        User user = userRepository.save(user("withdraw@ove.com", "탈퇴회원"));
        User other = userRepository.save(user("keep@ove.com", "남는회원"));
        pushDeviceRepository.save(device(user, "token-1", true));
        pushDeviceRepository.save(device(user, "token-2", false));
        pushDeviceRepository.save(device(other, "token-3", true));

        int deleted = pushDeviceRepository.deleteAllByUserId(user.getId());

        assertThat(deleted).isEqualTo(2);
        assertThat(pushDeviceRepository.findAll()).extracting(PushDevice::getPushToken)
                .containsExactly("token-3");
    }

    private User user(String email, String nickname) {
        return User.builder().email(email).password("hashed").nickname(nickname).build();
    }

    private PushDevice device(User user, String token, boolean alertEnabled) {
        return PushDevice.builder()
                .user(user)
                .platform(DevicePlatform.ANDROID)
                .pushToken(token)
                .alertEnabled(alertEnabled)
                .build();
    }
}
