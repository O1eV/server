package com.ove.push.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import com.ove.push.domain.PushDevice;
import com.ove.push.repository.PushDeviceRepository;
import com.ove.signal.event.SignalChangedEvent;
import com.ove.watchlist.repository.WatchlistRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 신호가 바뀐 뒤, 그 종목을 관심 종목에 넣고 알림을 켠 사용자의 기기로 푸시를 보낸다.
 * 신호 저장 트랜잭션이 커밋된 뒤에만 실행해서, 롤백된 신호로 알림이 나가지 않게 한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PushNotificationService {

    private static final BigDecimal PERCENT = BigDecimal.valueOf(100);

    private final PushDeviceRepository pushDeviceRepository;
    private final WatchlistRepository watchlistRepository;
    private final PushSender pushSender;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handleSignalChanged(SignalChangedEvent event) {
        // 신호 저장은 이미 커밋됐다. 알림 조회·발송이 실패해도 신호 저장 호출자에게 예외를 돌려주지 않는다.
        try {
            dispatch(event);
        } catch (RuntimeException e) {
            log.warn("신호 변경 알림 처리 실패: symbol={}", event.symbol(), e);
        }
    }

    private void dispatch(SignalChangedEvent event) {
        List<UUID> userIds = watchlistRepository.findUserIdsBySymbol(event.symbol());
        if (userIds.isEmpty()) {
            return;
        }

        List<PushDevice> devices = pushDeviceRepository.findAllByUserIdInAndAlertEnabledTrue(userIds);
        for (PushDevice device : devices) {
            try {
                // 기기 하나가 실패해도 같은 종목을 보는 다른 기기의 알림은 계속 보낸다.
                pushSender.send(new PushMessage(
                        device.getId(),
                        device.getPushToken(),
                        event.symbol() + " AI 신호 변경",
                        body(event),
                        data(event)));
            } catch (RuntimeException e) {
                log.warn("신호 푸시 발송 실패: deviceId={}, symbol={}", device.getId(), event.symbol(), e);
            }
        }
    }

    private String body(SignalChangedEvent event) {
        return event.previousSignal() + " → " + event.signal()
                + " (확신도 " + event.confidence().multiply(PERCENT).setScale(1, RoundingMode.HALF_UP) + "%)";
    }

    private Map<String, String> data(SignalChangedEvent event) {
        return Map.of(
                "type", "SIGNAL_CHANGED",
                "signalId", String.valueOf(event.signalId()),
                "symbol", event.symbol(),
                "previousSignal", event.previousSignal().name(),
                "signal", event.signal().name(),
                "confidence", event.confidence().toPlainString(),
                "price", event.price().toPlainString(),
                "generatedAt", event.generatedAt().toString());
    }
}
