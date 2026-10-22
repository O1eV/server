package com.ove.market.collector;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.PatternTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

import com.ove.support.Await;
import com.ove.support.CollectorContainers;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 실제 Redis(Testcontainers)에서 현재가 해시와 Pub/Sub 발행을 확인한다. Docker 필요.
 */
class MarketRedisPublisherTest {

    private static final Instant T0 = Instant.parse("2026-10-20T12:00:00Z");

    private static LettuceConnectionFactory connectionFactory;
    private static StringRedisTemplate redisTemplate;
    private static RedisMessageListenerContainer listenerContainer;
    private static final List<String[]> RECEIVED = new CopyOnWriteArrayList<>(); // {channel, message}

    private final ObjectMapper objectMapper = new ObjectMapper();
    private MarketRedisPublisher publisher;

    @BeforeAll
    static void setUpRedis() {
        connectionFactory = new LettuceConnectionFactory(CollectorContainers.redisHost(),
                CollectorContainers.redisPort());
        connectionFactory.afterPropertiesSet();
        redisTemplate = new StringRedisTemplate(connectionFactory);
        redisTemplate.afterPropertiesSet();

        listenerContainer = new RedisMessageListenerContainer();
        listenerContainer.setConnectionFactory(connectionFactory);
        listenerContainer.afterPropertiesSet();
        listenerContainer.start();
        listenerContainer.addMessageListener((message, pattern) -> RECEIVED.add(new String[] {
                new String(message.getChannel()), new String(message.getBody())}),
                List.of(new PatternTopic("prices:*"), new PatternTopic("candles.closed:*")));
    }

    @AfterAll
    static void tearDownRedis() throws Exception {
        listenerContainer.stop();
        listenerContainer.destroy();
        connectionFactory.destroy();
    }

    @BeforeEach
    void setUp() {
        redisTemplate.getConnectionFactory().getConnection().serverCommands().flushAll();
        RECEIVED.clear();
        publisher = new MarketRedisPublisher(redisTemplate, objectMapper, Duration.ofMinutes(10),
                Duration.ofSeconds(1));
    }

    @Test
    @DisplayName("체결마다 price:{symbol} 해시에 price·tradeTimestamp·updatedAt을 쓰고 changeRate는 넣지 않는다")
    void writesPriceHash() {
        publisher.onTrade(trade("KRW-BTC", "150000000.5", T0.minusMillis(30)), T0);

        Map<Object, Object> hash = redisTemplate.opsForHash().entries("price:KRW-BTC");
        assertThat(hash).containsEntry("price", "150000000.5")
                .containsEntry("tradeTimestamp", T0.minusMillis(30).toString())
                .containsEntry("updatedAt", T0.toString())
                .doesNotContainKey("changeRate");
    }

    @Test
    @DisplayName("해시에는 설정한 만료 시간(TTL)이 걸린다")
    void hashHasTtl() {
        publisher.onTrade(trade("KRW-BTC", "100", T0), T0);

        Long ttl = redisTemplate.getExpire("price:KRW-BTC");
        assertThat(ttl).isBetween(590L, 600L);
    }

    @Test
    @DisplayName("tick은 종목당 초당 최대 1회: 첫 체결은 바로 발행하고, 1초 안의 체결은 모아 두었다가 1초가 지나면 마지막 값 하나만 발행")
    void throttlesTicksToOnePerSecond() {
        publisher.onTrade(trade("KRW-BTC", "100", T0), T0);
        publisher.onTrade(trade("KRW-BTC", "101", T0.plusMillis(300)), T0.plusMillis(300));
        publisher.onTrade(trade("KRW-BTC", "102", T0.plusMillis(600)), T0.plusMillis(600));

        awaitMessages(1);
        publisher.flushPendingTicks(T0.plusMillis(999));
        assertThat(ticks("prices:KRW-BTC")).hasSize(1);

        publisher.flushPendingTicks(T0.plusMillis(1000));
        awaitMessages(2);
        List<JsonNode> ticks = ticks("prices:KRW-BTC");
        assertThat(ticks).hasSize(2);
        assertThat(ticks.get(0).get("price").decimalValue()).isEqualByComparingTo("100");
        assertThat(ticks.get(1).get("price").decimalValue()).isEqualByComparingTo("102");

        // 더 미뤄 둔 게 없으면 다시 flush해도 발행하지 않는다
        publisher.flushPendingTicks(T0.plusMillis(3000));
        assertThat(ticks("prices:KRW-BTC")).hasSize(2);
        // 해시에는 체결마다 반영되어 있다
        assertThat(redisTemplate.opsForHash().get("price:KRW-BTC", "price")).isEqualTo("102");
    }

    @Test
    @DisplayName("1초 넘게 간격을 두고 온 체결은 바로 발행한다")
    void publishesImmediatelyAfterInterval() {
        publisher.onTrade(trade("KRW-BTC", "100", T0), T0);
        publisher.onTrade(trade("KRW-BTC", "101", T0.plusMillis(1500)), T0.plusMillis(1500));

        awaitMessages(2);
        assertThat(ticks("prices:KRW-BTC")).hasSize(2);
    }

    @Test
    @DisplayName("tick 메시지에는 symbol·price·tradeTimestamp·updatedAt이 들어간다")
    void tickPayload() {
        publisher.onTrade(trade("KRW-ETH", "5000000", T0.minusMillis(5)), T0);

        awaitMessages(1);
        JsonNode tick = ticks("prices:KRW-ETH").get(0);
        assertThat(tick.get("symbol").asString()).isEqualTo("KRW-ETH");
        assertThat(tick.get("price").decimalValue()).isEqualByComparingTo("5000000");
        assertThat(tick.get("tradeTimestamp").asString()).isEqualTo(T0.minusMillis(5).toString());
        assertThat(tick.get("updatedAt").asString()).isEqualTo(T0.toString());
    }

    @Test
    @DisplayName("종목별로 따로 쓰로틀한다")
    void throttlesPerSymbol() {
        publisher.onTrade(trade("KRW-BTC", "100", T0), T0);
        publisher.onTrade(trade("KRW-ETH", "50", T0.plusMillis(100)), T0.plusMillis(100));

        awaitMessages(2);
        assertThat(ticks("prices:KRW-BTC")).hasSize(1);
        assertThat(ticks("prices:KRW-ETH")).hasSize(1);
    }

    @Test
    @DisplayName("이미 반영한 것보다 오래된 체결이 늦게 와도 현재가를 되돌리지 않는다")
    void ignoresOlderTrade() {
        publisher.onTrade(trade("KRW-BTC", "200", T0.plusMillis(500)), T0.plusMillis(500));
        publisher.onTrade(trade("KRW-BTC", "100", T0), T0.plusMillis(600));

        assertThat(redisTemplate.opsForHash().get("price:KRW-BTC", "price")).isEqualTo("200");
    }

    @Test
    @DisplayName("봉이 마감되면 candles.closed:{symbol}에 봉 내용을 발행한다")
    void publishesCandleClosed() {
        publisher.publishCandleClosed(new MinuteCandle("KRW-BTC", T0, new BigDecimal("100"), new BigDecimal("120"),
                new BigDecimal("90"), new BigDecimal("110"), new BigDecimal("5.5")));

        awaitMessages(1);
        String[] message = RECEIVED.get(0);
        assertThat(message[0]).isEqualTo("candles.closed:KRW-BTC");
        JsonNode candle = objectMapper.readTree(message[1]);
        assertThat(candle.get("symbol").asString()).isEqualTo("KRW-BTC");
        assertThat(candle.get("time").asString()).isEqualTo(T0.toString());
        assertThat(candle.get("open").decimalValue()).isEqualByComparingTo("100");
        assertThat(candle.get("high").decimalValue()).isEqualByComparingTo("120");
        assertThat(candle.get("low").decimalValue()).isEqualByComparingTo("90");
        assertThat(candle.get("close").decimalValue()).isEqualByComparingTo("110");
        assertThat(candle.get("volume").decimalValue()).isEqualByComparingTo("5.5");
    }

    private void awaitMessages(int count) {
        assertThat(Await.until(() -> RECEIVED.size() >= count)).as("Pub/Sub 메시지 %d개", count).isTrue();
    }

    private List<JsonNode> ticks(String channel) {
        return RECEIVED.stream().filter(message -> message[0].equals(channel))
                .map(message -> objectMapper.readTree(message[1])).toList();
    }

    private Trade trade(String symbol, String price, Instant tradeTimestamp) {
        return new Trade(symbol, new BigDecimal(price), new BigDecimal("1"), tradeTimestamp);
    }
}
