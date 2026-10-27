package com.ove.market.collector.kiwoom;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.ove.market.collector.MarketCollector;
import com.ove.market.collector.UpbitTradeSource;
import com.ove.support.Await;
import com.ove.support.CollectorContainers;
import com.ove.support.FakeKiwoomRestServer;
import com.ove.support.FakeKiwoomRestServer.Reply;
import com.ove.support.FakeUpbitWebSocketServer;
import com.ove.support.IntegrationTestSupport;
import com.ove.support.MutableClock;
import com.ove.support.RedisRecorder;

import tools.jackson.databind.JsonNode;

/**
 * collector.kiwoom.enabled=true로 앱 전체를 띄워서 설정 조립부터 끝까지 확인한다.
 * 가짜 키움 REST·WebSocket 서버 → 키움 수집기 → 실제 TimescaleDB·Redis. 실제 키움에는 연결하지 않는다. Docker 필요.
 */
class KiwoomCollectorEndToEndTest extends IntegrationTestSupport {

    // 2026-10-21 10:00:59 KST = 01:00:59 UTC
    private static final MutableClock CLOCK = new MutableClock(Instant.parse("2026-10-21T01:00:59Z"));
    private static final Instant LIVE_FROM = Instant.parse("2026-10-21T01:01:00Z"); // 10:01 KST
    private static FakeUpbitWebSocketServer wsServer;
    private static FakeKiwoomRestServer restServer;
    private static final AtomicInteger TOKENS = new AtomicInteger();

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private LettuceConnectionFactory redisConnectionFactory;

    @Autowired
    private ApplicationContext context;

    @TestConfiguration
    static class TestClockConfig {

        @Bean
        @Primary
        Clock testClock() {
            return CLOCK;
        }
    }

    @BeforeAll
    static void startFakeKiwoom() throws IOException {
        startServersIfNeeded();
    }

    @AfterAll
    static void stopFakeKiwoom() throws IOException {
        wsServer.close();
        restServer.close();
    }

    private static synchronized void startServersIfNeeded() throws IOException {
        if (restServer == null) {
            restServer = new FakeKiwoomRestServer();
            restServer.handle("/oauth2/token", request -> Reply.ok(FakeKiwoomRestServer.tokenJson(
                    "TEST-TOKEN-" + TOKENS.incrementAndGet(), "20261022160000")));
            restServer.handle("/oauth2/revoke", request -> Reply.ok("{\"return_code\":0,\"return_msg\":\"\"}"));
            // 접속 직전 분(10:00 KST)의 분봉 하나를 돌려준다 (cntr_tm이 봉 시작이라고 가정한 START 설정)
            restServer.handle("/api/dostk/chart", request -> Reply.ok("{\"stk_cd\":\"005930\","
                    + "\"stk_min_pole_chart_qry\":[{\"cur_prc\":\"-78500\",\"trde_qty\":\"1200\","
                    + "\"cntr_tm\":\"20261021100000\",\"open_pric\":\"-78400\",\"high_pric\":\"-78600\","
                    + "\"low_pric\":\"-78300\"}],\"return_code\":0,\"return_msg\":\"정상\"}"));
        }
        if (wsServer == null) {
            wsServer = new FakeUpbitWebSocketServer();
            wsServer.onClientText((connection, text) -> {
                try {
                    String trnm = text.contains("\"LOGIN\"") ? "LOGIN" : text.contains("\"REG\"") ? "REG" : "";
                    if (!trnm.isEmpty()) {
                        connection.sendText("{\"trnm\":\"" + trnm + "\",\"return_code\":0,\"return_msg\":\"\"}");
                    }
                } catch (IOException e) {
                    throw new IllegalStateException(e);
                }
            });
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) throws IOException {
        startServersIfNeeded();
        // 기존 통합 테스트의 H2 대신 TimescaleDB를 쓴다 (candles hypertable이 필요하므로)
        registry.add("spring.datasource.url", CollectorContainers.TIMESCALE::getJdbcUrl);
        registry.add("spring.datasource.username", CollectorContainers.TIMESCALE::getUsername);
        registry.add("spring.datasource.password", CollectorContainers.TIMESCALE::getPassword);
        registry.add("collector.kiwoom.enabled", () -> "true");
        registry.add("collector.kiwoom.symbols", () -> "005930");
        registry.add("collector.kiwoom.app-key", () -> "TEST-APP-KEY-not-real");
        registry.add("collector.kiwoom.app-secret", () -> "TEST-APP-SECRET-not-real");
        registry.add("collector.kiwoom.rest-base-url", () -> restServer.url());
        registry.add("collector.kiwoom.web-socket-url", () -> wsServer.url());
        registry.add("collector.kiwoom.backfill-enabled", () -> "true");
        registry.add("collector.kiwoom.minute-bar-label", () -> "START");
        registry.add("collector.grace-delay", () -> "200ms");
        registry.add("collector.tick-check-interval", () -> "100ms");
    }

    @Test
    void 키움_수집기를_켜면_장중_체결이_1분봉으로_저장되고_Redis로_나가고_업비트_수집기는_없다() throws Exception {
        // 설정 조립: 키움 수집기만 있고 업비트 수집기는 없다
        assertThat(context.getBeanNamesForType(MarketCollector.class)).containsExactly("kiwoomMarketCollector");
        assertThat(context.getBeanNamesForType(UpbitTradeSource.class)).isEmpty();

        try (RedisRecorder recorder = new RedisRecorder(redisConnectionFactory.getHostName(),
                redisConnectionFactory.getPort())) {
            // 앱 기동과 함께 가짜 키움에 연결해서 토큰 발급 → LOGIN → 종목(0B)·장시작시간(0s) 등록
            FakeUpbitWebSocketServer.Connection connection = wsServer.awaitConnection(1);
            assertThat(Await.until(() -> connection.receivedTexts().size() == 3)).isTrue();
            assertThat(connection.receivedTexts().get(0)).contains("\"LOGIN\"").contains("TEST-TOKEN-1");
            assertThat(connection.receivedTexts().get(1)).contains("005930").contains("0B");
            assertThat(connection.receivedTexts().get(2)).contains("0s");
            assertThat(restServer.requestsTo("/oauth2/token")).hasSize(1);

            // 장중 체결 3건 (10:01 KST) + 시간외 1건(무시되어야 함)
            connection.sendText(trade("100105", "-78800", "+10", "2"));
            connection.sendText(trade("100130", "+78900", "-5", "2"));
            connection.sendText(trade("100145", "-78700", "+7", "2"));
            connection.sendText(trade("100150", "-99999", "+999", "3"));

            // 현재가·tick
            assertThat(Await.until(() -> "78700".equals(
                    recorder.template().opsForHash().get("demo:price:005930", "price")))).isTrue();
            assertThat(Await.until(() -> !recorder.messagesOn("demo:prices:005930").isEmpty())).isTrue();
            // 주식(시연용) 시세는 코인과 같은 이름의 키·채널로는 나가지 않는다
            assertThat(recorder.template().hasKey("price:005930")).isFalse();
            assertThat(recorder.messagesOn("prices:005930")).isEmpty();

            // 접속 직전 분(10:00 KST)은 키움 분봉 REST로 채워진다 (접속한 분이 끝난 뒤 유예가 지나면 실행)
            assertThat(Await.until(() -> candleAt(Instant.parse("2026-10-21T01:00:00Z")) != null,
                    Duration.ofSeconds(10))).isTrue();
            assertThat((BigDecimal) candleAt(Instant.parse("2026-10-21T01:00:00Z")).get("close"))
                    .isEqualByComparingTo("78500");
            assertThat(restServer.requestsTo("/api/dostk/chart")).hasSize(1);
            JsonNode chartBody = objectMapper.readTree(restServer.requestsTo("/api/dostk/chart").get(0).body());
            assertThat(chartBody.get("stk_cd").asString()).isEqualTo("005930");

            // 10:01 봉 마감: 시계를 10:02:00.2 KST로 옮기면 주기 작업이 마감한다
            CLOCK.set(LIVE_FROM.plusSeconds(60).plusMillis(200));
            assertThat(Await.until(() -> candleAt(LIVE_FROM) != null)).isTrue();
            Map<String, Object> live = candleAt(LIVE_FROM);
            assertThat((BigDecimal) live.get("open")).isEqualByComparingTo("78800");
            assertThat((BigDecimal) live.get("high")).isEqualByComparingTo("78900");
            assertThat((BigDecimal) live.get("low")).isEqualByComparingTo("78700");
            assertThat((BigDecimal) live.get("close")).isEqualByComparingTo("78700");
            assertThat((BigDecimal) live.get("volume")).isEqualByComparingTo("22"); // 10 + 5 + 7, 시간외 999는 제외

            assertThat(Await.until(() -> recorder.messagesOn("demo:candles.closed:005930").size() == 1)).isTrue();
            JsonNode closed = objectMapper.readTree(recorder.messagesOn("demo:candles.closed:005930").get(0));
            assertThat(closed.get("time").asString()).isEqualTo(LIVE_FROM.toString());
            assertThat(closed.get("symbol").asString()).isEqualTo("005930");

            // 허용되지 않은 REST 호출은 없었다: 토큰 발급과 분봉 차트뿐
            assertThat(restServer.requests()).extracting(FakeKiwoomRestServer.Received::path)
                    .containsOnly("/oauth2/token", "/api/dostk/chart");
        }
    }

    private Map<String, Object> candleAt(Instant time) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT * FROM candles WHERE symbol = ? AND time = ?", "005930", Timestamp.from(time));
        return rows.isEmpty() ? null : rows.get(0);
    }

    private String trade(String time, String price, String volume, String session) {
        return "{\"trnm\":\"REAL\",\"data\":[{\"type\":\"0B\",\"name\":\"주식체결\",\"item\":\"005930\","
                + "\"values\":{\"20\":\"" + time + "\",\"10\":\"" + price + "\",\"15\":\"" + volume
                + "\",\"290\":\"" + session + "\"}}]}";
    }
}
