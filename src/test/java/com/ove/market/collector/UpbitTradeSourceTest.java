package com.ove.market.collector;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.random.RandomGenerator;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.ove.support.Await;
import com.ove.support.FakeUpbitWebSocketServer;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 가짜 WebSocket 서버로 업비트 수신부의 구독·메시지 해석·재연결·ping 동작을 확인한다(실제 업비트에는 연결하지 않는다).
 */
class UpbitTradeSourceTest {

    private static final String BTC_TRADE = """
            {"type":"trade","code":"KRW-BTC","trade_price":150000000.5,"trade_volume":0.0123,
             "trade_timestamp":1792497600123,"stream_type":"REALTIME","ask_bid":"BID","sequential_id":1}""";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private FakeUpbitWebSocketServer server;
    private UpbitTradeSource source;
    private Recorder recorder;

    @BeforeEach
    void setUp() throws Exception {
        server = new FakeUpbitWebSocketServer();
        recorder = new Recorder();
    }

    @AfterEach
    void tearDown() throws Exception {
        if (source != null) {
            source.stop();
        }
        server.close();
    }

    @Test
    @DisplayName("연결하면 종목 전체를 한 번에 구독하는 요청을 보낸다 (ticket, trade+대문자 codes+is_only_realtime, format)")
    void sendsSubscriptionForAllSymbols() throws Exception {
        startSource(Duration.ofSeconds(30), Duration.ofSeconds(60));

        FakeUpbitWebSocketServer.Connection connection = server.awaitConnection(1);
        assertThat(Await.until(() -> connection.receivedTexts().size() == 1)).isTrue();
        assertThat(recorder.connected.get()).isEqualTo(1);

        JsonNode request = objectMapper.readTree(connection.receivedTexts().get(0));
        assertThat(request.isArray()).isTrue();
        assertThat(request).hasSize(3);
        assertThat(request.get(0).get("ticket").asString()).isNotBlank();
        assertThat(request.get(1).get("type").asString()).isEqualTo("trade");
        assertThat(request.get(1).get("codes").valueStream().map(JsonNode::asString).toList())
                .containsExactly("KRW-BTC", "KRW-ETH");
        assertThat(request.get(1).get("is_only_realtime").asBoolean()).isTrue();
        assertThat(request.get(2).get("format").asString()).isEqualTo("DEFAULT");
    }

    @Test
    @DisplayName("binary 프레임으로 온 체결을 Trade로 해석해 리스너에 넘긴다")
    void parsesBinaryTrade() throws Exception {
        startSource(Duration.ofSeconds(30), Duration.ofSeconds(60));
        FakeUpbitWebSocketServer.Connection connection = server.awaitConnection(1);
        assertThat(Await.until(() -> recorder.connected.get() == 1)).isTrue();

        connection.sendBinary(BTC_TRADE);

        assertThat(Await.until(() -> recorder.trades.size() == 1)).isTrue();
        Trade trade = recorder.trades.get(0);
        assertThat(trade.symbol()).isEqualTo("KRW-BTC");
        assertThat(trade.price()).isEqualByComparingTo(new BigDecimal("150000000.5"));
        assertThat(trade.volume()).isEqualByComparingTo(new BigDecimal("0.0123"));
        assertThat(trade.tradeTimestamp()).isEqualTo(Instant.ofEpochMilli(1792497600123L));
    }

    @Test
    @DisplayName("여러 프레임으로 쪼개져 온 메시지와 text 프레임도 해석한다")
    void parsesFragmentedAndTextMessages() throws Exception {
        startSource(Duration.ofSeconds(30), Duration.ofSeconds(60));
        FakeUpbitWebSocketServer.Connection connection = server.awaitConnection(1);
        assertThat(Await.until(() -> recorder.connected.get() == 1)).isTrue();

        connection.sendBinaryFragmented(BTC_TRADE);
        connection.sendText(BTC_TRADE.replace("150000000.5", "150000001"));

        assertThat(Await.until(() -> recorder.trades.size() == 2)).isTrue();
        assertThat(recorder.trades.get(0).price()).isEqualByComparingTo("150000000.5");
        assertThat(recorder.trades.get(1).price()).isEqualByComparingTo("150000001");
    }

    @Test
    @DisplayName("SNAPSHOT·체결이 아닌 메시지·깨진 메시지는 건너뛰고 그 뒤 체결은 정상 처리한다")
    void skipsSnapshotAndInvalidMessages() throws Exception {
        startSource(Duration.ofSeconds(30), Duration.ofSeconds(60));
        FakeUpbitWebSocketServer.Connection connection = server.awaitConnection(1);
        assertThat(Await.until(() -> recorder.connected.get() == 1)).isTrue();

        connection.sendBinary(BTC_TRADE.replace("REALTIME", "SNAPSHOT"));
        connection.sendBinary("{\"status\":\"UP\"}");
        connection.sendBinary("not json at all");
        connection.sendBinary("{\"type\":\"trade\",\"code\":\"KRW-BTC\"}");
        connection.sendBinary(BTC_TRADE);

        assertThat(Await.until(() -> recorder.trades.size() == 1)).isTrue();
        Thread.sleep(200);
        assertThat(recorder.trades).hasSize(1);
        assertThat(recorder.trades.get(0).price()).isEqualByComparingTo("150000000.5");
    }

    @Test
    @DisplayName("서버가 연결을 끊으면 onDisconnected 후 다시 연결하고, 같은 종목 전체를 다시 구독한다")
    void reconnectsAfterServerClosesConnection() throws Exception {
        startSource(Duration.ofSeconds(30), Duration.ofSeconds(60));
        FakeUpbitWebSocketServer.Connection first = server.awaitConnection(1);
        assertThat(Await.until(() -> recorder.connected.get() == 1)).isTrue();

        first.close();

        assertThat(Await.until(() -> recorder.disconnected.get() == 1)).isTrue();
        FakeUpbitWebSocketServer.Connection second = server.awaitConnection(2);
        assertThat(Await.until(() -> recorder.connected.get() == 2)).isTrue();
        assertThat(Await.until(() -> second.receivedTexts().size() == 1)).isTrue();
        assertThat(second.receivedTexts().get(0)).contains("KRW-BTC").contains("KRW-ETH");
        // 다시 붙은 뒤에도 체결을 받는다
        second.sendBinary(BTC_TRADE);
        assertThat(Await.until(() -> recorder.trades.size() == 1)).isTrue();
    }

    @Test
    @DisplayName("끊김이 반복돼도 재연결 간격이 백오프 하한 아래로 내려가지 않는다 (연결 요청 한도 보호)")
    void reconnectAttemptsAreSpacedByBackoff() throws Exception {
        // 하한 300ms: 4번 끊어도 연결 시도는 그 간격 이상으로만 늘어야 한다
        startSource(Duration.ofSeconds(30), Duration.ofSeconds(60), Duration.ofMillis(300));

        long startedAt = System.nanoTime();
        for (int attempt = 1; attempt <= 4; attempt++) {
            server.awaitConnection(attempt).close();
        }
        server.awaitConnection(5);
        long elapsedMillis = Duration.ofNanos(System.nanoTime() - startedAt).toMillis();

        // 끊긴 뒤 다시 붙기까지 최소 300ms씩 4번
        assertThat(elapsedMillis).isGreaterThanOrEqualTo(1000);
    }

    @Test
    @DisplayName("주기적으로 ping 프레임을 보낸다")
    void sendsPing() throws Exception {
        startSource(Duration.ofMillis(100), Duration.ofSeconds(60));
        FakeUpbitWebSocketServer.Connection connection = server.awaitConnection(1);

        assertThat(Await.until(() -> connection.pingCount() >= 2)).isTrue();
    }

    @Test
    @DisplayName("한동안 아무것도 못 받으면(pong도 없으면) 죽은 연결로 보고 다시 연결한다")
    void reconnectsWhenIdle() throws Exception {
        server.setRespondToPings(false);
        startSource(Duration.ofMillis(100), Duration.ofMillis(400));
        server.awaitConnection(1);

        assertThat(Await.until(() -> recorder.disconnected.get() >= 1)).isTrue();
        server.awaitConnection(2);
    }

    @Test
    @DisplayName("유휴 감지는 ping 주기와 별개로 돈다: ping 주기가 30초여도 유휴 한계(0.3초) + 점검 주기(0.05초) 안에 재연결한다")
    void idleDetectionIsIndependentOfPingInterval() throws Exception {
        startSource(Duration.ofSeconds(30), Duration.ofMillis(300), Duration.ofMillis(50), Duration.ofMillis(10));
        FakeUpbitWebSocketServer.Connection first = server.awaitConnection(1);
        assertThat(Await.until(() -> recorder.connected.get() == 1)).isTrue();

        long connectedAt = System.nanoTime();
        assertThat(Await.until(() -> recorder.disconnected.get() == 1, Duration.ofSeconds(3))).isTrue();
        long detectedAfterMillis = Duration.ofNanos(System.nanoTime() - connectedAt).toMillis();

        // 서버가 아무것도 안 보냈고 ping도 아직 한 번도 안 나갔는데(30초 주기) 유휴로 감지됐다
        assertThat(first.pingCount()).isZero();
        assertThat(detectedAfterMillis).isBetween(250L, 1500L);
        server.awaitConnection(2);
    }

    @Test
    @DisplayName("마지막으로 받은 뒤 유휴 한계 + 점검 주기 안에 끊김을 감지한다 (기본값 90초 + 10초 ≈ 100초 안의 축소판)")
    void detectsWithinIdleTimeoutPlusCheckInterval() throws Exception {
        Duration idleTimeout = Duration.ofMillis(600);
        Duration checkInterval = Duration.ofMillis(100);
        startSource(Duration.ofSeconds(30), idleTimeout, checkInterval, Duration.ofMillis(10));
        FakeUpbitWebSocketServer.Connection connection = server.awaitConnection(1);
        assertThat(Await.until(() -> recorder.connected.get() == 1)).isTrue();

        connection.sendBinary(BTC_TRADE); // 마지막으로 받은 메시지
        assertThat(Await.until(() -> recorder.trades.size() == 1)).isTrue();
        long lastReceivedAt = System.nanoTime();

        assertThat(Await.until(() -> recorder.disconnected.get() == 1, Duration.ofSeconds(5))).isTrue();
        long detectedAfterMillis = Duration.ofNanos(System.nanoTime() - lastReceivedAt).toMillis();

        // 유휴 한계(600ms)보다 빠르면 안 되고, 한계 + 점검 주기(100ms) + 여유(400ms) 안에는 감지해야 한다
        assertThat(detectedAfterMillis).isBetween(idleTimeout.toMillis() - 50,
                idleTimeout.toMillis() + checkInterval.toMillis() + 400);
    }

    @Test
    @DisplayName("서버가 pong으로 응답하면 조용해도 연결을 유지한다")
    void keepsConnectionWhenPongsArrive() throws Exception {
        startSource(Duration.ofMillis(100), Duration.ofMillis(400));
        server.awaitConnection(1);

        Thread.sleep(1000);

        assertThat(server.connectionCount()).isEqualTo(1);
        assertThat(recorder.disconnected.get()).isZero();
    }

    @Test
    @DisplayName("stop 이후에는 다시 연결하지 않는다")
    void doesNotReconnectAfterStop() throws Exception {
        startSource(Duration.ofSeconds(30), Duration.ofSeconds(60));
        FakeUpbitWebSocketServer.Connection connection = server.awaitConnection(1);
        assertThat(Await.until(() -> recorder.connected.get() == 1)).isTrue();

        source.stop();
        connection.close();
        Thread.sleep(600);

        assertThat(server.connectionCount()).isEqualTo(1);
    }

    private void startSource(Duration pingInterval, Duration idleTimeout) {
        startSource(pingInterval, idleTimeout, Duration.ofMillis(10));
    }

    private void startSource(Duration pingInterval, Duration idleTimeout, Duration minReconnectDelay) {
        startSource(pingInterval, idleTimeout, Duration.ofMillis(50), minReconnectDelay);
    }

    private void startSource(Duration pingInterval, Duration idleTimeout, Duration idleCheckInterval,
                             Duration minReconnectDelay) {
        CollectorProperties properties = new CollectorProperties(true, List.of("krw-btc", "KRW-ETH"), server.url(),
                Duration.ofMillis(1500), Duration.ofMillis(250), Duration.ofSeconds(1), Duration.ofMinutes(10),
                Duration.ofMillis(20), Duration.ofMillis(200), minReconnectDelay, pingInterval, idleTimeout,
                idleCheckInterval, Duration.ofSeconds(5), 3);
        // 지터를 없애(난수 1.0) 대기 시간이 항상 계산값 그대로 나오게 한다
        ReconnectBackoff backoff = new ReconnectBackoff(properties.reconnectInitialDelay(),
                properties.reconnectMaxDelay(), properties.reconnectMinDelay(), new RandomGenerator() {
                    @Override
                    public long nextLong() {
                        return 0;
                    }

                    @Override
                    public double nextDouble() {
                        return 1.0;
                    }
                });
        source = new UpbitTradeSource(properties, objectMapper, backoff, Clock.systemUTC());
        source.start(recorder);
    }

    private static final class Recorder implements TradeSource.TradeListener {

        final AtomicInteger connected = new AtomicInteger();
        final AtomicInteger disconnected = new AtomicInteger();
        final List<Trade> trades = new CopyOnWriteArrayList<>();

        @Override
        public void onConnected(Instant connectedAt) {
            connected.incrementAndGet();
        }

        @Override
        public void onTrade(Trade trade) {
            trades.add(trade);
        }

        @Override
        public void onDisconnected() {
            disconnected.incrementAndGet();
        }
    }
}
