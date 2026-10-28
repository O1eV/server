package com.ove.realtime.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import com.ove.realtime.dto.AlertMessage;
import com.ove.realtime.dto.PriceMessage;
import com.ove.realtime.dto.SignalMessage;
import com.ove.watchlist.repository.WatchlistRepository;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class RealtimePublisher {

    private final com.ove.signal.service.SignalService signalService;
    private final SimpMessagingTemplate messagingTemplate;
    private final WatchlistRepository watchlistRepository;

    // 현재가를 /topic/prices/{symbol} 구독자에게 보낼 때 사용
    public void publishPrice(PriceMessage price) {
        messagingTemplate.convertAndSend("/topic/prices/" + price.getSymbol(), price);
    }

    // 바뀐 신호를 /topic/signals/{symbol} 구독자와, 그 종목을 관심 종목에 넣은 사람(/user/queue/alerts)에게 보낼 때 사용
    public void publishSignalChange(String symbol, String from, String to, BigDecimal confidence, boolean stale) {
        publishSignalChange(symbol, from, to, null, confidence, stale);
    }

    public void publishSignalChange(String symbol, String from, String to,
            java.util.Map<String, BigDecimal> probabilities, BigDecimal confidence, boolean stale) {
        Instant now = Instant.now();
        if (probabilities != null) {
            if (!probabilities.keySet().containsAll(java.util.Set.of("buy", "hold", "sell"))) {
                throw new com.ove.global.exception.BusinessException(com.ove.global.exception.ErrorCode.INVALID_REQUEST);
            }
            signalService.record(new com.ove.signal.domain.Signal(symbol, from, to,
                    probabilities.get("buy"), probabilities.get("hold"), probabilities.get("sell"), confidence,
                    null, now, null));
        }
        messagingTemplate.convertAndSend("/topic/signals/" + symbol, new SignalMessage(symbol, from, to, probabilities, confidence, stale, now));

        // 이전 신호가 없는 첫 신호나 같은 신호가 이어지는 경우는 "신호 변경"이 아니라서 알림을 보내지 않는다.
        if (from == null) {
            return;
        }
        AlertMessage alert = new AlertMessage(symbol, from, to, confidence, now);
        for (UUID userId : watchlistRepository.findUserIdsBySymbol(symbol)) {
            messagingTemplate.convertAndSendToUser(userId.toString(), "/queue/alerts", alert);
        }
    }

    // 모델 호출이 실패해 마지막 신호를 그대로 보여 줄 때 사용: stale=true로 /topic/signals/{symbol}에만 보낸다(저장·알림 없음).
    public void publishStaleSignal(String symbol, String signal, BigDecimal confidence) {
        messagingTemplate.convertAndSend("/topic/signals/" + symbol,
                new SignalMessage(symbol, signal, confidence, true, Instant.now()));
    }
}
