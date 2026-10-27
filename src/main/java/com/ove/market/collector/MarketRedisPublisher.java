package com.ove.market.collector;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.data.redis.core.StringRedisTemplate;

import tools.jackson.databind.ObjectMapper;

/**
 * 현재가를 Redis 해시에 저장하고, tick과 봉 마감을 Pub/Sub으로 발행한다.
 * 해시는 체결마다 갱신하지만 tick은 종목당 tickPublishInterval(1초)에 한 번만 낸다.
 * 시간은 밖에서 넘겨받아서(now) 시계에 의존하지 않는다.
 */
public class MarketRedisPublisher {

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final Duration priceTtl;
    private final Duration tickInterval;
    // Redis 키·채널 이름 앞에 붙는 접두사. 업비트(코인)는 "", 키움(주식 시연용)은 "demo:"라서 서로 키가 섞이지 않는다.
    private final String keyPrefix;

    private final Map<String, Instant> latestTradeAt = new HashMap<>();
    private final Map<String, Instant> lastTickAt = new HashMap<>();
    private final Map<String, Trade> pendingTicks = new HashMap<>();

    public MarketRedisPublisher(StringRedisTemplate redisTemplate, ObjectMapper objectMapper,
                                Duration priceTtl, Duration tickInterval) {
        this(redisTemplate, objectMapper, priceTtl, tickInterval, "");
    }

    public MarketRedisPublisher(StringRedisTemplate redisTemplate, ObjectMapper objectMapper,
                                Duration priceTtl, Duration tickInterval, String keyPrefix) {
        this.keyPrefix = keyPrefix;
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.priceTtl = priceTtl;
        this.tickInterval = tickInterval;
    }

    /**
     * 체결을 받아 현재가 해시를 갱신하고, 마지막 tick 발행 후 간격이 지났으면 tick을 발행한다.
     * 이미 반영한 것보다 오래된 체결이 늦게 오면 현재가를 되돌리지 않도록 무시한다.
     */
    public synchronized void onTrade(Trade trade, Instant now) {
        Instant latest = latestTradeAt.get(trade.symbol());
        if (latest != null && trade.tradeTimestamp().isBefore(latest)) {
            return;
        }
        latestTradeAt.put(trade.symbol(), trade.tradeTimestamp());

        // changeRate는 체결 메시지로는 알 수 없어서 이번에는 넣지 않는다.
        String key = keyPrefix + CollectorKeys.priceKey(trade.symbol());
        Map<String, String> hash = new LinkedHashMap<>();
        hash.put("price", trade.price().toPlainString());
        hash.put("tradeTimestamp", trade.tradeTimestamp().toString());
        hash.put("updatedAt", now.toString());
        redisTemplate.opsForHash().putAll(key, hash);
        redisTemplate.expire(key, priceTtl);

        Instant lastTick = lastTickAt.get(trade.symbol());
        if (lastTick == null || !now.isBefore(lastTick.plus(tickInterval))) {
            publishTick(trade, now);
        } else {
            pendingTicks.put(trade.symbol(), trade);
        }
    }

    /**
     * 간격 때문에 미뤄 둔 마지막 tick을 간격이 지난 뒤에 발행한다. 체결이 뜸해져도 마지막 가격이 전달되게 한다.
     */
    public synchronized void flushPendingTicks(Instant now) {
        for (Map.Entry<String, Trade> entry : Map.copyOf(pendingTicks).entrySet()) {
            Instant lastTick = lastTickAt.get(entry.getKey());
            if (lastTick == null || !now.isBefore(lastTick.plus(tickInterval))) {
                publishTick(entry.getValue(), now);
            }
        }
    }

    public void publishCandleClosed(MinuteCandle candle) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("symbol", candle.symbol());
        payload.put("time", candle.time().toString());
        payload.put("open", candle.open());
        payload.put("high", candle.high());
        payload.put("low", candle.low());
        payload.put("close", candle.close());
        payload.put("volume", candle.volume());
        redisTemplate.convertAndSend(keyPrefix + CollectorKeys.candleClosedChannel(candle.symbol()),
                objectMapper.writeValueAsString(payload));
    }

    private void publishTick(Trade trade, Instant now) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("symbol", trade.symbol());
        payload.put("price", trade.price());
        payload.put("tradeTimestamp", trade.tradeTimestamp().toString());
        payload.put("updatedAt", now.toString());
        redisTemplate.convertAndSend(keyPrefix + CollectorKeys.tickChannel(trade.symbol()),
                objectMapper.writeValueAsString(payload));
        lastTickAt.put(trade.symbol(), now);
        pendingTicks.remove(trade.symbol());
    }
}
