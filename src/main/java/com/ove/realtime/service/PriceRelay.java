package com.ove.realtime.service;

import java.time.Instant;

import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.stereotype.Component;

import com.ove.realtime.dto.PriceMessage;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Slf4j
@Component
@RequiredArgsConstructor
public class PriceRelay implements MessageListener {

    private final RealtimePublisher realtimePublisher;
    private final ObjectMapper objectMapper;

    // 수집기가 Redis(prices:{symbol})로 낸 현재가를 WebSocket 메시지로 바꿔 보낼 때 사용
    @Override
    public void onMessage(Message message, byte[] pattern) {
        try {
            JsonNode tick = objectMapper.readTree(message.getBody());
            realtimePublisher.publishPrice(new PriceMessage(
                    tick.path("symbol").asString(),
                    tick.path("price").decimalValue(),
                    Instant.parse(tick.path("tradeTimestamp").asString())));
        } catch (RuntimeException e) {
            log.warn("Failed to relay price tick: {}", e.getMessage());
        }
    }
}
