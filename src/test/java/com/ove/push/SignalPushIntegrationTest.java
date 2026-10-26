package com.ove.push;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.times;

import java.math.BigDecimal;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.ove.auth.repository.TokenRedisRepository;
import com.ove.push.domain.DevicePlatform;
import com.ove.push.domain.PushDevice;
import com.ove.push.repository.PushDeviceRepository;
import com.ove.push.service.PushSender;
import com.ove.signal.domain.SignalType;
import com.ove.signal.repository.SignalRepository;
import com.ove.signal.service.SignalService;
import com.ove.user.domain.User;
import com.ove.user.repository.UserRepository;
import com.ove.watchlist.domain.Watchlist;
import com.ove.watchlist.repository.WatchlistRepository;

/**
 * 신호 저장 커밋 → AFTER_COMMIT 리스너 → 관심 종목 사용자 기기 발송까지 실제 빈으로 연결해 확인한다.
 * Redis는 JWT 필터가 참조하는 토큰 저장소만 목으로 바꿔 Docker 없이 돌린다.
 */
@SpringBootTest
class SignalPushIntegrationTest {

    @Autowired
    private SignalService signalService;

    @Autowired
    private SignalRepository signalRepository;

    @Autowired
    private WatchlistRepository watchlistRepository;

    @Autowired
    private PushDeviceRepository pushDeviceRepository;

    @Autowired
    private UserRepository userRepository;

    @MockitoBean
    private PushSender pushSender;

    @MockitoBean
    private TokenRedisRepository tokenRedisRepository;

    @AfterEach
    void tearDown() {
        pushDeviceRepository.deleteAllInBatch();
        watchlistRepository.deleteAllInBatch();
        signalRepository.deleteAllInBatch();
        userRepository.deleteAllInBatch();
    }

    @Test
    @DisplayName("신호가 바뀌면 그 종목을 관심 종목에 넣은 사용자의 알림 켠 기기로 푸시를 보낸다")
    void changedSignalSendsPushAfterCommit() {
        User user = userRepository.save(User.builder()
                .email("signal-push@ove.com")
                .password("hashed-password")
                .nickname("알림")
                .build());
        watchlistRepository.save(Watchlist.builder().userId(user.getId()).symbol("KRW-BTC").build());
        PushDevice device = pushDeviceRepository.save(PushDevice.builder()
                .user(user)
                .platform(DevicePlatform.ANDROID)
                .pushToken("token-1")
                .alertEnabled(true)
                .build());

        // 첫 신호(HOLD)는 전환이 아니므로 알림이 없다.
        signalService.record("KRW-BTC", SignalType.HOLD, new BigDecimal("0.2"), new BigDecimal("0.7"),
                new BigDecimal("0.1"), new BigDecimal("0.7"), new BigDecimal("1000"));
        then(pushSender).shouldHaveNoInteractions();

        // 두 번째 신호(BUY)는 전환이므로 커밋 뒤 한 번 발송한다.
        signalService.record("KRW-BTC", SignalType.BUY, new BigDecimal("0.7"), new BigDecimal("0.2"),
                new BigDecimal("0.1"), new BigDecimal("0.9"), new BigDecimal("1100"));

        then(pushSender).should(times(1)).send(argThat(message ->
                message.deviceId().equals(device.getId())
                        && message.title().equals("KRW-BTC AI 신호 변경")
                        && message.body().equals("HOLD → BUY (확신도 90.0%)")
                        && message.data().get("previousSignal").equals("HOLD")
                        && message.data().get("signal").equals("BUY")));
        assertThat(signalRepository.count()).isEqualTo(2);
    }
}
