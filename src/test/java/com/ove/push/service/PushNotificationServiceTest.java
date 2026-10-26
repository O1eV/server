package com.ove.push.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.times;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import com.ove.push.domain.DevicePlatform;
import com.ove.push.domain.PushDevice;
import com.ove.push.repository.PushDeviceRepository;
import com.ove.signal.domain.SignalType;
import com.ove.signal.event.SignalChangedEvent;
import com.ove.user.domain.User;
import com.ove.watchlist.repository.WatchlistRepository;

@ExtendWith(MockitoExtension.class)
class PushNotificationServiceTest {

    private static final Instant GENERATED_AT = Instant.parse("2026-10-26T00:00:00Z");

    @Mock
    private PushDeviceRepository pushDeviceRepository;

    @Mock
    private WatchlistRepository watchlistRepository;

    @Mock
    private PushSender pushSender;

    private PushNotificationService pushNotificationService;

    @BeforeEach
    void setUp() {
        pushNotificationService = new PushNotificationService(pushDeviceRepository, watchlistRepository, pushSender);
    }

    @Test
    @DisplayName("관심 종목 사용자가 없으면 기기 조회도 발송도 하지 않는다")
    void noWatchers() {
        given(watchlistRepository.findUserIdsBySymbol("KRW-BTC")).willReturn(List.of());

        pushNotificationService.handleSignalChanged(event());

        then(pushDeviceRepository).shouldHaveNoInteractions();
        then(pushSender).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("관심 종목 사용자의 알림 켠 기기로 신호 전환 내용을 보낸다")
    void sendsToWatchers() {
        UUID userId = UUID.randomUUID();
        PushDevice device = device(11L, userId, "token-11", true);
        given(watchlistRepository.findUserIdsBySymbol("KRW-BTC")).willReturn(List.of(userId));
        given(pushDeviceRepository.findAllByUserIdInAndAlertEnabledTrue(List.of(userId))).willReturn(List.of(device));

        pushNotificationService.handleSignalChanged(event());

        ArgumentCaptor<PushMessage> messageCaptor = ArgumentCaptor.forClass(PushMessage.class);
        then(pushSender).should().send(messageCaptor.capture());
        PushMessage message = messageCaptor.getValue();
        assertThat(message.deviceId()).isEqualTo(11L);
        assertThat(message.token()).isEqualTo("token-11");
        assertThat(message.title()).isEqualTo("KRW-BTC AI 신호 변경");
        assertThat(message.body()).isEqualTo("HOLD → BUY (확신도 87.5%)");
        assertThat(message.data())
                .containsEntry("type", "SIGNAL_CHANGED")
                .containsEntry("signalId", "7")
                .containsEntry("symbol", "KRW-BTC")
                .containsEntry("previousSignal", "HOLD")
                .containsEntry("signal", "BUY")
                .containsEntry("confidence", "0.875")
                .containsEntry("price", "1000")
                .containsEntry("generatedAt", "2026-10-26T00:00:00Z");
    }

    @Test
    @DisplayName("한 기기 발송이 실패해도 다른 기기의 알림은 계속 보낸다")
    void failureOfOneDeviceDoesNotStopOthers() {
        UUID userId = UUID.randomUUID();
        PushDevice first = device(1L, userId, "token-1", true);
        PushDevice second = device(2L, userId, "token-2", true);
        given(watchlistRepository.findUserIdsBySymbol("KRW-BTC")).willReturn(List.of(userId));
        given(pushDeviceRepository.findAllByUserIdInAndAlertEnabledTrue(List.of(userId)))
                .willReturn(List.of(first, second));
        willThrow(new IllegalStateException("provider down")).given(pushSender).send(
                new PushMessage(1L, "token-1", "KRW-BTC AI 신호 변경", "HOLD → BUY (확신도 87.5%)", data()));

        pushNotificationService.handleSignalChanged(event());

        then(pushSender).should(times(2)).send(any(PushMessage.class));
    }

    @Test
    @DisplayName("알림 대상 조회가 실패해도 이미 커밋된 신호 저장 호출자에게 예외를 돌려주지 않는다")
    void lookupFailureDoesNotPropagate() {
        given(watchlistRepository.findUserIdsBySymbol("KRW-BTC"))
                .willThrow(new IllegalStateException("db down"));

        assertThatCode(() -> pushNotificationService.handleSignalChanged(event()))
                .doesNotThrowAnyException();
        then(pushSender).shouldHaveNoInteractions();
    }

    private SignalChangedEvent event() {
        return new SignalChangedEvent(7L, "KRW-BTC", SignalType.HOLD, SignalType.BUY,
                new BigDecimal("0.875"), new BigDecimal("1000"), GENERATED_AT);
    }

    private Map<String, String> data() {
        return Map.of(
                "type", "SIGNAL_CHANGED",
                "signalId", "7",
                "symbol", "KRW-BTC",
                "previousSignal", "HOLD",
                "signal", "BUY",
                "confidence", "0.875",
                "price", "1000",
                "generatedAt", "2026-10-26T00:00:00Z");
    }

    private PushDevice device(Long id, UUID userId, String token, boolean alertEnabled) {
        User user = User.builder().email("push-" + id + "@ove.com").password("hashed").nickname("푸시").build();
        ReflectionTestUtils.setField(user, "id", userId);
        PushDevice device = PushDevice.builder()
                .user(user)
                .platform(DevicePlatform.ANDROID)
                .pushToken(token)
                .alertEnabled(alertEnabled)
                .build();
        ReflectionTestUtils.setField(device, "id", id);
        return device;
    }
}
