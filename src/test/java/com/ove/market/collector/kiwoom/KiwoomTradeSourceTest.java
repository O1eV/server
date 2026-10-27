package com.ove.market.collector.kiwoom;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.random.RandomGenerator;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;

import com.ove.market.collector.CollectorProperties;
import com.ove.market.collector.ReconnectBackoff;
import com.ove.market.collector.Trade;
import com.ove.market.collector.TradeSource;
import com.ove.support.Await;
import com.ove.support.FakeKiwoomRestServer;
import com.ove.support.FakeKiwoomRestServer.Reply;
import com.ove.support.FakeUpbitWebSocketServer;
import com.ove.support.FakeUpbitWebSocketServer.Connection;
import com.ove.support.MutableClock;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 가짜 키움 WebSocket·REST 서버로 LOGIN → 종목 등록 → 체결 수신 → PING 에코 → 재연결 → 장중 유휴 감지를 확인한다.
 * 실제 키움에는 연결하지 않는다. 서버 쪽 메시지 형식은 키움 공식 스펙 JSON과 총괄이 전달한 LOGIN/PING 사양을 따른 것이다.
 */
class KiwoomTradeSourceTest {

    private static final String APP_KEY = "TEST-APP-KEY-not-real";
    private static final String APP_SECRET = "TEST-APP-SECRET-not-real";
    // 2026-10-21 10:00:00 KST = 01:00:00 UTC
    private static final Instant TEN_AM_KST = Instant.parse("2026-10-21T01:00:00Z");

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Queue<Integer> loginCodes = new ConcurrentLinkedQueue<>();
    private final Queue<Integer> regCodes = new ConcurrentLinkedQueue<>();
    private final AtomicBoolean answerLogin = new AtomicBoolean(true);
    // null이 아니면 LOGIN마다 이 코드로 계속 실패시킨다(loginCodes보다 우선)
    private volatile Integer alwaysLoginCode;
    private final AtomicInteger issuedTokens = new AtomicInteger();
    private final Recorder recorder = new Recorder();

    private FakeUpbitWebSocketServer ws;
    private FakeKiwoomRestServer rest;
    private MutableClock clock;
    private KiwoomTradeSource source;
    private ListAppender<ILoggingEvent> logs;
    private Logger kiwoomLogger;

    @BeforeEach
    void setUp() throws Exception {
        clock = new MutableClock(TEN_AM_KST);
        rest = new FakeKiwoomRestServer();
        rest.handle("/oauth2/token", request -> Reply.ok(FakeKiwoomRestServer.tokenJson(
                "TEST-TOKEN-" + issuedTokens.incrementAndGet(), "20261022160000")));
        rest.handle("/oauth2/revoke", request -> Reply.ok("{\"return_code\":0,\"return_msg\":\"정상\"}"));
        ws = new FakeUpbitWebSocketServer();
        ws.onClientText(this::answer);

        kiwoomLogger = (Logger) LoggerFactory.getLogger("com.ove.market.collector.kiwoom");
        logs = new ListAppender<>();
        logs.start();
        kiwoomLogger.addAppender(logs);
        kiwoomLogger.setLevel(Level.DEBUG);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (source != null) {
            source.stop();
        }
        kiwoomLogger.detachAppender(logs);
        ws.close();
        rest.close();
    }

    // 가짜 서버의 자동 응답: LOGIN, REG 요청에 return_code를 돌려준다. 코드 큐가 비어 있으면 0(정상)
    private void answer(Connection connection, String text) {
        try {
            JsonNode message = objectMapper.readTree(text);
            switch (message.path("trnm").asString("")) {
                case "LOGIN" -> {
                    if (answerLogin.get()) {
                        Integer code = alwaysLoginCode != null ? alwaysLoginCode : loginCodes.poll();
                        push(connection, "{\"trnm\":\"LOGIN\",\"return_code\":" + (code == null ? 0 : code)
                                + ",\"return_msg\":\"" + (code == null ? "" : "login failed") + "\"}");
                    }
                }
                case "REG" -> {
                    Integer code = regCodes.poll();
                    push(connection, "{\"trnm\":\"REG\",\"return_code\":" + (code == null ? 0 : code)
                            + ",\"return_msg\":\"\"}");
                }
                default -> {
                }
            }
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    // ---------- LOGIN · 종목 등록 ----------

    @Test
    @DisplayName("연결하면 LOGIN → 0B 종목 등록 → 0s 장시작시간 등록 순서로 보내고, 모두 성공해야 onConnected")
    void loginAndRegister() throws Exception {
        startSource();

        Connection connection = ws.awaitConnection(1);
        assertThat(Await.until(() -> recorder.connected.get() == 1)).isTrue();

        List<String> texts = connection.receivedTexts();
        assertThat(texts).hasSize(3);
        JsonNode login = objectMapper.readTree(texts.get(0));
        assertThat(login.get("trnm").asString()).isEqualTo("LOGIN");
        assertThat(login.get("token").asString()).isEqualTo("TEST-TOKEN-1");

        JsonNode stockReg = objectMapper.readTree(texts.get(1));
        assertThat(stockReg.get("trnm").asString()).isEqualTo("REG");
        assertThat(stockReg.get("grp_no").asString()).isEqualTo("1");
        assertThat(stockReg.get("refresh").asString()).isEqualTo("1");
        assertThat(stockReg.get("data").get(0).get("type").valueStream().map(JsonNode::asString).toList())
                .containsExactly("0B");
        assertThat(stockReg.get("data").get(0).get("item").valueStream().map(JsonNode::asString).toList())
                .containsExactly("005930", "000660");

        JsonNode hoursReg = objectMapper.readTree(texts.get(2));
        assertThat(hoursReg.get("grp_no").asString()).isEqualTo("2");
        assertThat(hoursReg.get("data").get(0).get("type").valueStream().map(JsonNode::asString).toList())
                .containsExactly("0s");
        assertThat(hoursReg.get("data").get(0).get("item").valueStream().map(JsonNode::asString).toList())
                .containsExactly("");
    }

    @Test
    @DisplayName("시세 TR(0B, 0s)과 LOGIN 외에는 서버로 아무것도 보내지 않는다 (주문·계좌 TR 등록 경로 없음)")
    void sendsOnlyAllowedMessages() throws Exception {
        startSource();
        Connection connection = ws.awaitConnection(1);
        assertThat(Await.until(() -> recorder.connected.get() == 1)).isTrue();
        push(connection, "{\"trnm\":\"PING\"}");
        assertThat(Await.until(() -> connection.receivedTexts().size() == 4)).isTrue();

        for (String text : connection.receivedTexts()) {
            JsonNode message = objectMapper.readTree(text);
            String trnm = message.get("trnm").asString();
            assertThat(trnm).isIn("LOGIN", "REG", "PING");
            if ("REG".equals(trnm)) {
                message.get("data").forEach(entry -> entry.get("type").forEach(type ->
                        assertThat(type.asString()).isIn("0B", "0s")));
            }
        }
    }

    @Test
    @DisplayName("LOGIN이 토큰 문제(8010)로 실패하면 연결하지 않고, 토큰을 새로 발급받아 다시 시도한다")
    void loginFailureWithTokenProblemReissuesToken() throws Exception {
        loginCodes.add(8010);
        startSource();

        assertThat(Await.until(() -> recorder.connected.get() == 1)).isTrue();

        assertThat(ws.connectionCount()).isEqualTo(2);
        assertThat(rest.requestsTo("/oauth2/token")).hasSize(2);
        JsonNode secondLogin = objectMapper.readTree(ws.awaitConnection(2).receivedTexts().get(0));
        assertThat(secondLogin.get("token").asString()).isEqualTo("TEST-TOKEN-2");
        assertThat(recorder.disconnected.get()).isZero(); // 한 번도 연결된 적이 없으니 끊김 알림도 없다
    }

    @Test
    @DisplayName("LOGIN이 토큰과 무관한 이유(8030 실전/모의 혼용)로 실패하면 같은 토큰으로 재시도한다")
    void loginFailureWithoutTokenProblemKeepsToken() throws Exception {
        loginCodes.add(8030);
        startSource();

        assertThat(Await.until(() -> recorder.connected.get() == 1)).isTrue();

        assertThat(rest.requestsTo("/oauth2/token")).hasSize(1);
        JsonNode secondLogin = objectMapper.readTree(ws.awaitConnection(2).receivedTexts().get(0));
        assertThat(secondLogin.get("token").asString()).isEqualTo("TEST-TOKEN-1");
    }

    @Test
    @DisplayName("종목 등록(REG)이 실패하면(return_code 1) 연결을 끊고 처음부터 다시 한다")
    void registrationFailureReconnects() {
        regCodes.add(1);
        startSource();

        assertThat(Await.until(() -> recorder.connected.get() == 1)).isTrue();

        assertThat(ws.connectionCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("토큰을 받지 못하면(8010) WebSocket에 연결하지 않고 백오프 후 토큰부터 다시 받는다")
    void tokenIssueFailureRetries() {
        AtomicInteger calls = new AtomicInteger();
        rest.handle("/oauth2/token", request -> calls.getAndIncrement() == 0
                ? Reply.ok("{\"return_code\":8010,\"return_msg\":\"IP 불일치\"}")
                : Reply.ok(FakeKiwoomRestServer.tokenJson("TEST-TOKEN-OK", "20261022160000")));
        startSource();

        assertThat(Await.until(() -> recorder.connected.get() == 1)).isTrue();

        assertThat(rest.requestsTo("/oauth2/token")).hasSize(2);
        assertThat(ws.connectionCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("LOGIN·등록이 설정 시간(15초) 안에 끝나지 않으면 연결을 다시 맺는다")
    void setupTimeout() {
        answerLogin.set(false); // 서버가 LOGIN에 응답하지 않는다
        startSource();
        ws.awaitConnection(1);

        answerLogin.set(true);
        advance(Duration.ofSeconds(16));

        assertThat(Await.until(() -> recorder.connected.get() == 1)).isTrue();
        assertThat(ws.connectionCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("LOGIN이 계속 실패하면 재연결 대기 시간이 늘어나고, 성공(등록 완료)한 뒤에야 처음으로 돌아간다")
    void loginFailuresBackOffExponentially() {
        for (int i = 0; i < 5; i++) {
            loginCodes.add(8010);
        }
        RecordingBackoff backoff = new RecordingBackoff();
        startSource(backoff);

        assertThat(Await.until(() -> recorder.connected.get() == 1)).isTrue();

        // 20ms → 40 → 80 → 160 → 200(상한). LOGIN 응답을 받았다고 되돌리지 않는다.
        assertThat(backoff.delays).containsExactly(20L, 40L, 80L, 160L, 200L);
        assertThat(backoff.resets.get()).isEqualTo(1); // 등록까지 끝난 한 번뿐
        // 토큰 오류마다 이전 토큰을 폐기하고 새로 받는다
        assertThat(rest.requestsTo("/oauth2/token")).hasSize(6);
        assertThat(rest.requestsTo("/oauth2/revoke")).hasSize(5);
        assertThat(rest.requestsTo("/oauth2/revoke").get(0).body()).contains("TEST-TOKEN-1");
    }

    @Test
    @DisplayName("종목 등록(REG)이 계속 실패해도 재연결 대기 시간이 늘어난다")
    void registrationFailuresBackOffExponentially() {
        for (int i = 0; i < 4; i++) {
            regCodes.add(1);
        }
        RecordingBackoff backoff = new RecordingBackoff();
        startSource(backoff);

        assertThat(Await.until(() -> recorder.connected.get() == 1)).isTrue();

        assertThat(backoff.delays).containsExactly(20L, 40L, 80L, 160L);
        assertThat(backoff.resets.get()).isEqualTo(1);
        assertThat(rest.requestsTo("/oauth2/token")).hasSize(1); // 토큰 문제가 아니라서 토큰은 그대로 쓴다
    }

    @Test
    @DisplayName("LOGIN이 계속 실패하면 실제 백오프 설정(1초~)으로 1.5초 동안 연결·토큰 발급이 한 자릿수에 그친다")
    void persistentLoginFailureDoesNotHammerServer() throws Exception {
        alwaysLoginCode = 8010;
        startSource(new ReconnectBackoff(Duration.ofSeconds(1), Duration.ofSeconds(30), Duration.ofMillis(250),
                RandomGenerator.getDefault()));

        Thread.sleep(1500);

        assertThat(ws.connectionCount()).isBetween(1, 3);
        assertThat(rest.requestsTo("/oauth2/token").size()).isLessThan(10);
    }

    // ---------- PING · 체결 ----------

    @Test
    @DisplayName("서버가 보낸 PING은 받은 그대로 되돌려 보낸다")
    void echoesPing() {
        startSource();
        Connection connection = ws.awaitConnection(1);
        assertThat(Await.until(() -> recorder.connected.get() == 1)).isTrue();

        String ping = "{\"trnm\":\"PING\",\"seq\":\"7\"}";
        push(connection, ping);

        assertThat(Await.until(() -> connection.receivedTexts().contains(ping))).isTrue();
        assertThat(connection.receivedTexts().stream().filter(ping::equals)).hasSize(1);
    }

    @Test
    @DisplayName("0B 체결을 Trade로 해석한다: 현재가·거래량 부호 제거, 체결시간 KST → UTC")
    void parsesTrade() {
        startSource();
        Connection connection = connected();

        push(connection, real0B("005930", "100005", "-78800", "+82", "2"));

        assertThat(Await.until(() -> recorder.trades.size() == 1)).isTrue();
        Trade trade = recorder.trades.get(0);
        assertThat(trade.symbol()).isEqualTo("005930");
        assertThat(trade.price()).isEqualByComparingTo("78800");
        assertThat(trade.volume()).isEqualByComparingTo("82");
        assertThat(trade.tradeTimestamp()).isEqualTo(Instant.parse("2026-10-21T01:00:05Z")); // 10:00:05 KST
    }

    @Test
    @DisplayName("장중이 아닌 체결(장구분 1·3), 구독하지 않은 종목, 시간외 시각은 무시하고 이후 체결은 정상 처리한다")
    void ignoresNonRegularTrades() {
        startSource();
        Connection connection = connected();

        push(connection, real0B("005930", "100005", "1000", "+1", "1")); // 장전 시간외
        push(connection, real0B("005930", "100006", "1000", "+1", "3")); // 장후 시간외
        push(connection, real0B("035720", "100007", "1000", "+1", "2")); // 구독하지 않은 종목
        push(connection, real0B("005930", "081500", "1000", "+1", "2")); // 장 시작 전 시각
        push(connection, real0B("000660", "100008", "+210000", "-3", "2"));

        assertThat(Await.until(() -> recorder.trades.size() == 1)).isTrue();
        assertThat(recorder.trades.get(0).symbol()).isEqualTo("000660");
        assertThat(recorder.trades.get(0).price()).isEqualByComparingTo("210000");
    }

    @Test
    @DisplayName("깨진 메시지와 모르는 메시지는 건너뛰고 연결을 유지한다")
    void ignoresGarbage() {
        startSource();
        Connection connection = connected();

        push(connection, "not json");
        push(connection, "{\"trnm\":\"SOMETHING\"}");
        push(connection, real0B("005930", "100005", "1000", "+1", "2"));

        assertThat(Await.until(() -> recorder.trades.size() == 1)).isTrue();
        assertThat(ws.connectionCount()).isEqualTo(1);
    }

    // ---------- 재연결 ----------

    @Test
    @DisplayName("연결이 끊기면 onDisconnected 후 다시 연결해 LOGIN과 종목 등록을 처음부터 다시 한다")
    void reconnectRelogsInAndReregisters() throws Exception {
        startSource();
        Connection first = connected();

        first.close();

        assertThat(Await.until(() -> recorder.disconnected.get() == 1)).isTrue();
        Connection second = ws.awaitConnection(2);
        assertThat(Await.until(() -> recorder.connected.get() == 2)).isTrue();
        List<String> texts = second.receivedTexts();
        assertThat(texts).hasSize(3);
        assertThat(objectMapper.readTree(texts.get(0)).get("trnm").asString()).isEqualTo("LOGIN");
        assertThat(objectMapper.readTree(texts.get(1)).get("data").get(0).get("type").get(0).asString())
                .isEqualTo("0B");
        assertThat(objectMapper.readTree(texts.get(2)).get("data").get(0).get("type").get(0).asString())
                .isEqualTo("0s");
        push(second, real0B("005930", "100009", "1000", "+1", "2"));
        assertThat(Await.until(() -> recorder.trades.size() == 1)).isTrue();
    }

    @Test
    @DisplayName("재연결 때 토큰이 만료 임박이면 새 토큰으로 LOGIN한다")
    void reconnectUsesFreshTokenNearExpiry() throws Exception {
        // 토큰 만료 2026-10-22 16:00 KST = 07:00 UTC. 시계를 만료 9분 전으로 옮기면(여유 10분) 재발급 대상이 된다.
        startSource();
        Connection first = connected();

        clock.set(Instant.parse("2026-10-22T06:51:00Z"));
        first.close();

        Connection second = ws.awaitConnection(2);
        assertThat(Await.until(() -> recorder.connected.get() == 2)).isTrue();
        assertThat(objectMapper.readTree(second.receivedTexts().get(0)).get("token").asString())
                .isEqualTo("TEST-TOKEN-2");
        assertThat(rest.requestsTo("/oauth2/token")).hasSize(2);
    }

    @Test
    @DisplayName("stop 이후에는 다시 연결하지 않는다")
    void doesNotReconnectAfterStop() throws Exception {
        startSource();
        Connection connection = connected();

        source.stop();
        connection.close();
        Thread.sleep(500);

        assertThat(ws.connectionCount()).isEqualTo(1);
    }

    // ---------- 장중에만 하는 유휴 감지 ----------

    @Test
    @DisplayName("장 상태를 모르는 동안(체결·0s 없음)에는 오래 조용해도 다시 연결하지 않는다 (휴장일·장외)")
    void noIdleReconnectWhenMarketStateUnknown() throws Exception {
        startSource();
        connected();

        advance(Duration.ofMinutes(30));
        Thread.sleep(400);

        assertThat(ws.connectionCount()).isEqualTo(1);
        assertThat(recorder.disconnected.get()).isZero();
    }

    @Test
    @DisplayName("0s로 장시작(3)을 받은 뒤 장중에 유휴 한계(120초)를 넘기면 다시 연결한다")
    void idleReconnectWhenOpenByMarketHoursMessage() {
        startSource();
        Connection connection = connected();
        push(connection, real0s("3", "100000"));
        awaitProcessed(connection);

        advance(Duration.ofSeconds(121));

        assertThat(Await.until(() -> recorder.disconnected.get() == 1)).isTrue();
        ws.awaitConnection(2);
    }

    @Test
    @DisplayName("0s를 못 받았어도 장중 체결을 받으면 장중으로 보고, 이후 유휴 한계를 넘기면 다시 연결한다")
    void idleReconnectWhenOpenByTrade() {
        startSource();
        Connection connection = connected();
        push(connection, real0B("005930", "100001", "1000", "+1", "2"));
        assertThat(Await.until(() -> recorder.trades.size() == 1)).isTrue();

        advance(Duration.ofSeconds(121));

        assertThat(Await.until(() -> recorder.disconnected.get() == 1)).isTrue();
    }

    // 호출마다 테스트 인스턴스와 가짜 서버가 새로 만들어져서, 앞선 코드의 연결·카운트가 섞이지 않는다
    @ParameterizedTest(name = "장 운영 구분 코드 {0}을 받으면 유휴 감지를 끈다")
    @ValueSource(strings = {"4", "2", "0", "8", "b"})
    @DisplayName("장마감(4)·마감 알림(2)·장시작전(0)·정규장마감(8)·시간외(b) 코드를 받으면 유휴 감지를 끈다")
    void noIdleReconnectAfterMarketClose(String closedCode) throws Exception {
        startSource();
        Connection connection = connected();
        push(connection, real0s("3", "090000"));
        push(connection, real0s(closedCode, "153000"));
        awaitProcessed(connection);

        advance(Duration.ofMinutes(10));
        Thread.sleep(300);

        assertThat(ws.connectionCount()).isEqualTo(1);
        assertThat(recorder.disconnected.get()).isZero();
    }

    @Test
    @DisplayName("정규장이 끝난 시각(15:35 KST) 이후에는 장중 상태로 남아 있어도 유휴 감지를 하지 않는다")
    void noIdleReconnectAfterRegularSession() throws Exception {
        startSource();
        Connection connection = connected();
        push(connection, real0s("3", "090000"));
        awaitProcessed(connection);

        clock.set(Instant.parse("2026-10-21T06:40:00Z")); // 15:40 KST
        Thread.sleep(400);

        assertThat(ws.connectionCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("서버 PING이 오는 동안은 체결이 없어도 살아 있는 연결로 본다")
    void pingKeepsConnectionAlive() throws Exception {
        startSource();
        Connection connection = connected();
        push(connection, real0s("3", "100000"));
        awaitProcessed(connection);

        advance(Duration.ofSeconds(100));
        push(connection, "{\"trnm\":\"PING\"}");
        awaitProcessed(connection);
        advance(Duration.ofSeconds(100));
        Thread.sleep(300);
        assertThat(ws.connectionCount()).isEqualTo(1);

        advance(Duration.ofSeconds(130)); // 이제는 PING도 끊겼다
        assertThat(Await.until(() -> recorder.disconnected.get() == 1)).isTrue();
    }

    // ---------- 비밀 값 ----------

    @Test
    @DisplayName("정상 흐름과 실패 흐름 모두에서 토큰·App Key·Secret이 로그에 남지 않는다")
    void noSecretsInLogs() {
        loginCodes.add(8010);
        startSource();
        Connection connection = null;
        assertThat(Await.until(() -> recorder.connected.get() == 1)).isTrue();
        connection = ws.awaitConnection(2);
        push(connection, real0B("005930", "100005", "1000", "+1", "2"));
        assertThat(Await.until(() -> recorder.trades.size() == 1)).isTrue();
        connection.close();
        assertThat(Await.until(() -> recorder.disconnected.get() == 1)).isTrue();

        List<String> lines = new ArrayList<>();
        for (ILoggingEvent event : new ArrayList<>(logs.list)) {
            lines.add(event.getFormattedMessage());
            if (event.getThrowableProxy() != null) {
                lines.add(event.getThrowableProxy().getMessage());
            }
        }
        assertThat(lines).isNotEmpty();
        assertThat(String.join("\n", lines)).doesNotContain("TEST-TOKEN").doesNotContain(APP_KEY)
                .doesNotContain(APP_SECRET);
    }

    // ---------- helpers ----------

    private Connection connected() {
        Connection connection = ws.awaitConnection(1);
        assertThat(Await.until(() -> recorder.connected.get() == 1)).isTrue();
        return connection;
    }

    // 서버가 보낸 메시지를 소스가 처리했는지 확인하는 동기화 지점: PING을 하나 더 보내 에코가 돌아오면 앞선 메시지는 처리된 것이다
    private void awaitProcessed(Connection connection) {
        String marker = "{\"trnm\":\"PING\",\"marker\":\"" + System.nanoTime() + "\"}";
        push(connection, marker);
        assertThat(Await.until(() -> connection.receivedTexts().contains(marker))).isTrue();
    }

    // 서버가 클라이언트로 text 프레임을 보낸다(체크 예외를 테스트 오류로 바꾼다)
    private void push(Connection connection, String text) {
        try {
            connection.sendText(text);
        } catch (java.io.IOException e) {
            throw new AssertionError(e);
        }
    }

    private void advance(Duration duration) {
        clock.set(clock.instant().plus(duration));
    }

    private String real0B(String item, String time, String price, String volume, String session) {
        return "{\"trnm\":\"REAL\",\"data\":[{\"type\":\"0B\",\"name\":\"주식체결\",\"item\":\"" + item
                + "\",\"values\":{\"20\":\"" + time + "\",\"10\":\"" + price + "\",\"15\":\"" + volume
                + "\",\"290\":\"" + session + "\"}}]}";
    }

    private String real0s(String operationCode, String time) {
        return "{\"trnm\":\"REAL\",\"data\":[{\"type\":\"0s\",\"name\":\"장시작시간\",\"item\":\"\","
                + "\"values\":{\"215\":\"" + operationCode + "\",\"20\":\"" + time + "\",\"214\":\"000000\"}}]}";
    }

    private void startSource() {
        startSource(new ReconnectBackoff(Duration.ofMillis(20), Duration.ofMillis(200),
                Duration.ofMillis(10), new RandomGenerator() {
                    @Override
                    public long nextLong() {
                        return 0;
                    }

                    @Override
                    public double nextDouble() {
                        return 1.0;
                    }
                }));
    }

    private void startSource(ReconnectBackoff backoff) {
        KiwoomProperties properties = new KiwoomProperties(true, List.of("005930", "000660"), APP_KEY, APP_SECRET,
                rest.url(), ws.url(), Duration.ofMinutes(10), Duration.ofSeconds(120), Duration.ofSeconds(15),
                Duration.ZERO, Duration.ofSeconds(1), Duration.ofSeconds(2), false, null, "1");
        CollectorProperties collectorProperties = new CollectorProperties(false, List.of("KRW-BTC"), "wss://unused",
                Duration.ofMillis(1500), Duration.ofMillis(250), Duration.ofSeconds(1), Duration.ofMinutes(10),
                Duration.ofMillis(20), Duration.ofMillis(200), Duration.ofMillis(10), Duration.ofSeconds(60),
                Duration.ofSeconds(90), Duration.ofMillis(50), Duration.ofSeconds(5), 3);
        KiwoomRestClient restClient = new KiwoomRestClient(rest.url(), Duration.ofSeconds(1), Duration.ofSeconds(2),
                Duration.ZERO, objectMapper);
        KiwoomTokenProvider tokenProvider = new KiwoomTokenProvider(restClient, properties, clock);
        source = new KiwoomTradeSource(properties, collectorProperties, tokenProvider, objectMapper, backoff, clock);
        source.start(recorder);
    }

    // 대기 시간과 reset 호출을 기록하는 백오프(무작위 없이 최대값으로 고정)
    private static final class RecordingBackoff extends ReconnectBackoff {

        final List<Long> delays = new CopyOnWriteArrayList<>();
        final AtomicInteger resets = new AtomicInteger();

        RecordingBackoff() {
            super(Duration.ofMillis(20), Duration.ofMillis(200), Duration.ofMillis(10), new RandomGenerator() {
                @Override
                public long nextLong() {
                    return 0;
                }

                @Override
                public double nextDouble() {
                    return 1.0;
                }
            });
        }

        @Override
        public Duration nextDelay() {
            Duration delay = super.nextDelay();
            delays.add(delay.toMillis());
            return delay;
        }

        @Override
        public void reset() {
            resets.incrementAndGet();
            super.reset();
        }
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
