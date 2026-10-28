package com.ove.realtime.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.BDDMockito.given;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import com.ove.signal.service.SignalService;
import com.ove.watchlist.repository.WatchlistRepository;

class RealtimePublisherSignalTest {

    SignalService signalService = mock(SignalService.class);
    SimpMessagingTemplate template = mock(SimpMessagingTemplate.class);
    WatchlistRepository watchlist = mock(WatchlistRepository.class);
    RealtimePublisher publisher = new RealtimePublisher(signalService, template, watchlist);

    private static final Map<String, BigDecimal> PROBS = Map.of("buy", new BigDecimal("0.7"),
            "hold", new BigDecimal("0.2"), "sell", new BigDecimal("0.1"));

    @Test
    @DisplayName("이전 신호가 없으면(첫 신호·같은 신호 지속) 저장과 토픽 갱신은 하되 관심종목 알림은 보내지 않는다")
    void noAlertWithoutPreviousSignal() {
        publisher.publishSignalChange("KRW-BTC", null, "BUY", PROBS, new BigDecimal("0.7"), false);

        verify(signalService).record(any());
        verify(template).convertAndSend(eq("/topic/signals/KRW-BTC"), any(Object.class));
        verifyNoInteractions(watchlist);
    }

    @Test
    @DisplayName("신호가 바뀌면 관심종목 사용자에게 알림을 보낸다")
    void alertOnChange() {
        UUID user = UUID.randomUUID();
        given(watchlist.findUserIdsBySymbol("KRW-BTC")).willReturn(List.of(user));

        publisher.publishSignalChange("KRW-BTC", "HOLD", "BUY", PROBS, new BigDecimal("0.7"), false);

        verify(template).convertAndSendToUser(eq(user.toString()), eq("/queue/alerts"), any(Object.class));
    }

    @Test
    @DisplayName("stale 신호는 토픽에만 보내고 저장·알림은 하지 않는다")
    void staleOnlyTopic() {
        publisher.publishStaleSignal("KRW-BTC", "HOLD", new BigDecimal("0.5"));

        verify(template).convertAndSend(eq("/topic/signals/KRW-BTC"), any(Object.class));
        verifyNoInteractions(signalService, watchlist);
    }
}
