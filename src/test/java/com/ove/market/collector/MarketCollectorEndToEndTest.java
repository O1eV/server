package com.ove.market.collector;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;

import java.io.IOException;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.ove.market.client.UpbitCandleResponse;
import com.ove.market.client.UpbitClient;
import com.ove.support.Await;
import com.ove.support.CollectorContainers;
import com.ove.support.FakeUpbitWebSocketServer;
import com.ove.support.IntegrationTestSupport;
import com.ove.support.MutableClock;
import com.ove.support.RedisRecorder;

import tools.jackson.databind.JsonNode;

/**
 * collector.enabled=true로 앱 전체를 띄워서 설정 조립부터 끝까지 확인한다.
 * 가짜 업비트 WebSocket 서버 → 수집기 → 실제 TimescaleDB·Redis. 실제 업비트에는 연결하지 않는다. Docker 필요.
 */
class MarketCollectorEndToEndTest extends IntegrationTestSupport {

    private static final Instant M0 = Instant.parse("2026-10-20T12:00:00Z");
    private static final MutableClock CLOCK = new MutableClock(M0.plusSeconds(59));
    private static FakeUpbitWebSocketServer upbitServer;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private LettuceConnectionFactory redisConnectionFactory;

    @MockitoBean
    private UpbitClient upbitClient;

    @TestConfiguration
    static class TestClockConfig {

        @Bean
        @Primary
        Clock testClock() {
            return CLOCK;
        }
    }

    @BeforeAll
    static void startFakeUpbit() throws IOException {
        upbitServer = new FakeUpbitWebSocketServer();
    }

    @AfterAll
    static void stopFakeUpbit() throws IOException {
        upbitServer.close();
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) throws IOException {
        // 기존 통합 테스트의 H2 대신 TimescaleDB를 쓴다 (candles hypertable이 필요하므로)
        registry.add("spring.datasource.url", CollectorContainers.TIMESCALE::getJdbcUrl);
        registry.add("spring.datasource.username", CollectorContainers.TIMESCALE::getUsername);
        registry.add("spring.datasource.password", CollectorContainers.TIMESCALE::getPassword);
        registry.add("collector.enabled", () -> "true");
        registry.add("collector.symbols", () -> "KRW-BTC,KRW-ETH");
        registry.add("collector.web-socket-url", () -> {
            try {
                if (upbitServer == null) {
                    upbitServer = new FakeUpbitWebSocketServer();
                }
                return upbitServer.url();
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        });
        registry.add("collector.grace-delay", () -> "200ms");
        registry.add("collector.tick-check-interval", () -> "100ms");
    }

    @Test
    void 앱이_수집기를_켜면_체결이_봉으로_저장되고_Redis로_나가고_접속_직전_분은_REST로_보충된다() throws Exception {
        Instant liveFrom = M0.plusSeconds(60);
        // 접속 직전 분(12:00)은 REST 보충으로 채워진다
        given(upbitClient.getCandles(eq("minutes/1"), eq("KRW-BTC"), eq(liveFrom), eq(200)))
                .willReturn(List.of(upbitCandle(M0, "90")));
        given(upbitClient.getCandles(eq("minutes/1"), eq("KRW-ETH"), eq(liveFrom), eq(200))).willReturn(List.of());

        try (RedisRecorder recorder = new RedisRecorder(redisConnectionFactory.getHostName(),
                redisConnectionFactory.getPort())) {
            // 수집기가 앱 기동과 함께 시작되어 가짜 업비트에 연결하고, 종목 전체를 한 번에 구독한다
            FakeUpbitWebSocketServer.Connection connection = upbitServer.awaitConnection(1);
            assertThat(Await.until(() -> connection.receivedTexts().size() == 1)).isTrue();
            assertThat(connection.receivedTexts().get(0)).contains("KRW-BTC", "KRW-ETH", "\"trade\"");
            assertThat(Await.until(() -> tableExists("candles"))).isTrue();

            // 12:00 체결은 접속한 분이라 봉에 쓰이지 않고(REST 값이 남음), 12:01 체결은 실시간으로 집계된다
            connection.sendBinary(trade("KRW-BTC", "95", "1", M0.plusSeconds(30)));
            connection.sendBinary(trade("KRW-BTC", "100", "1", liveFrom.plusSeconds(5)));
            connection.sendBinary(trade("KRW-BTC", "130", "2", liveFrom.plusSeconds(20)));
            connection.sendBinary(trade("KRW-BTC", "110", "1", liveFrom.plusSeconds(50)));
            connection.sendBinary("{\"type\":\"trade\",\"code\":\"KRW-BTC\",\"trade_price\":999,"
                    + "\"trade_volume\":9,\"trade_timestamp\":" + liveFrom.plusSeconds(51).toEpochMilli()
                    + ",\"stream_type\":\"SNAPSHOT\"}");

            // 현재가와 tick
            assertThat(Await.until(() -> "110".equals(
                    recorder.template().opsForHash().get("price:KRW-BTC", "price")))).isTrue();
            assertThat(Await.until(() -> !recorder.messagesOn("prices:KRW-BTC").isEmpty())).isTrue();

            // 수집된 동일한 해시가 브라우저용 REST 응답까지 전달되는지 확인한다.
            mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                    .get("/api/markets/KRW-BTC/price"))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.data.symbol").value("KRW-BTC"))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.data.price").value(110));

            // 접속 직전 분 보충 (접속한 분이 끝나고 유예가 지나면 실행: 시계 12:00:59 → 약 1.2초 뒤)
            assertThat(Await.until(() -> candleAt("KRW-BTC", M0) != null, Duration.ofSeconds(10))).isTrue();
            assertThat((BigDecimal) candleAt("KRW-BTC", M0).get("open")).isEqualByComparingTo("90");

            // 12:01봉 마감: 시계를 12:02:00.2(유예 후)로 옮기면 주기 작업이 마감한다
            CLOCK.set(liveFrom.plusSeconds(60).plusMillis(200));
            assertThat(Await.until(() -> candleAt("KRW-BTC", liveFrom) != null)).isTrue();
            Map<String, Object> live = candleAt("KRW-BTC", liveFrom);
            assertThat((BigDecimal) live.get("open")).isEqualByComparingTo("100");
            assertThat((BigDecimal) live.get("high")).isEqualByComparingTo("130");
            assertThat((BigDecimal) live.get("low")).isEqualByComparingTo("100");
            assertThat((BigDecimal) live.get("close")).isEqualByComparingTo("110");
            assertThat((BigDecimal) live.get("volume")).isEqualByComparingTo("4");

            assertThat(Await.until(() -> recorder.messagesOn("candles.closed:KRW-BTC").size() == 1)).isTrue();
            JsonNode closed = objectMapper.readTree(recorder.messagesOn("candles.closed:KRW-BTC").get(0));
            assertThat(closed.get("time").asString()).isEqualTo(liveFrom.toString());
            // 보충으로 채운 12:00봉은 마감 알림이 나가지 않았다 (12:01봉 알림 1건뿐)
            assertThat(recorder.messagesOn("candles.closed:KRW-BTC")).hasSize(1);
        }
    }

    private boolean tableExists(String name) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM information_schema.tables WHERE table_name = ?", Integer.class, name);
        return count != null && count == 1;
    }

    private Map<String, Object> candleAt(String symbol, Instant time) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT * FROM candles WHERE symbol = ? AND time = ?", symbol, Timestamp.from(time));
        return rows.isEmpty() ? null : rows.get(0);
    }

    private String trade(String code, String price, String volume, Instant timestamp) {
        return "{\"type\":\"trade\",\"code\":\"" + code + "\",\"trade_price\":" + price + ",\"trade_volume\":"
                + volume + ",\"trade_timestamp\":" + timestamp.toEpochMilli() + ",\"stream_type\":\"REALTIME\"}";
    }

    private UpbitCandleResponse upbitCandle(Instant time, String price) {
        String utc = time.toString();
        return UpbitCandleResponse.builder()
                .candleDateTimeUtc(utc.substring(0, utc.length() - 1))
                .openingPrice(new BigDecimal(price))
                .highPrice(new BigDecimal(price))
                .lowPrice(new BigDecimal(price))
                .tradePrice(new BigDecimal(price))
                .candleAccTradeVolume(BigDecimal.ONE)
                .build();
    }
}
