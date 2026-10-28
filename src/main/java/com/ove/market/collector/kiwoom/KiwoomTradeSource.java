package com.ove.market.collector.kiwoom;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import com.ove.market.collector.CollectorProperties;
import com.ove.market.collector.ReconnectBackoff;
import com.ove.market.collector.Trade;
import com.ove.market.collector.TradeSource;

import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 키움 REST API의 실시간 WebSocket에서 주식 체결(0B)을 받는다. 시세만 받고 주문·계좌 TR은 등록하지 않는다.
 *
 * 연결 순서: 접속 → LOGIN{trnm, token} → 종목 등록 REG(0B) → 장시작시간 등록 REG(0s) → onConnected.
 * 서버가 보내는 PING은 받은 그대로 되돌려 보낸다. 끊기면 지수 백오프로 다시 연결하고 LOGIN·REG를 처음부터 한다.
 *
 * 유휴 감지는 "장중으로 확인된 때"에만 한다. 장외·휴장일에는 체결이 없는 게 정상이라서다.
 * 장중 여부는 0s(장시작시간)의 장운영구분과 실제로 받은 장중 체결로 판단하고, 휴장일 달력은 쓰지 않는다
 * (키움에 달력 API가 없고, 휴장일은 0s의 "장시작" 알림이 오지 않는 것으로 알 수 있다).
 */
@Slf4j
public class KiwoomTradeSource implements TradeSource {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    // 정규장이 끝난 뒤에는 0s를 놓쳤더라도 유휴 감지를 하지 않는다
    private static final LocalTime IDLE_CHECK_UNTIL = LocalTime.of(15, 35);

    private final KiwoomProperties properties;
    private final CollectorProperties collectorProperties;
    private final KiwoomTokenProvider tokenProvider;
    private final ObjectMapper objectMapper;
    private final ReconnectBackoff backoff;
    private final Clock clock;
    private final Set<String> symbols;
    private final HttpClient httpClient = HttpClient.newHttpClient();

    // 연결을 새로 맺거나 잃을 때마다 올라가는 번호. 이미 버린 연결에서 늦게 오는 콜백을 구분하려고 쓴다.
    private final AtomicLong generation = new AtomicLong();

    private volatile TradeListener listener;
    private volatile ScheduledExecutorService scheduler;
    private volatile boolean running;
    private volatile WebSocket webSocket;
    private volatile Stage stage = Stage.IDLE;
    private volatile Phase phase = Phase.UNKNOWN;
    private volatile Instant openedAt;
    private volatile Instant lastReceivedAt;
    private int registrationIndex;
    private CompletableFuture<?> lastSend = CompletableFuture.completedFuture(null);

    private enum Stage { IDLE, CONNECTING, LOGIN_SENT, REGISTERING, ACTIVE }

    /** 장 운영 상태. UNKNOWN은 아직 모르는 상태(연결 직후), OPEN일 때만 유휴 감지를 한다. */
    private enum Phase { UNKNOWN, OPEN, CLOSED }

    public KiwoomTradeSource(KiwoomProperties properties, CollectorProperties collectorProperties,
                             KiwoomTokenProvider tokenProvider, ObjectMapper objectMapper, ReconnectBackoff backoff,
                             Clock clock) {
        this.properties = properties;
        this.collectorProperties = collectorProperties;
        this.tokenProvider = tokenProvider;
        this.objectMapper = objectMapper;
        this.backoff = backoff;
        this.clock = clock;
        this.symbols = Set.copyOf(properties.normalizedSymbols());
    }

    @Override
    public void start(TradeListener listener) {
        this.listener = listener;
        this.running = true;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "kiwoom-trade-source");
            thread.setDaemon(true);
            return thread;
        });
        scheduler.execute(this::connect);
        long checkMillis = collectorProperties.idleCheckInterval().toMillis();
        scheduler.scheduleWithFixedDelay(this::checkConnection, checkMillis, checkMillis, TimeUnit.MILLISECONDS);
    }

    @Override
    public void stop() {
        running = false;
        generation.incrementAndGet();
        WebSocket current = webSocket;
        if (current != null) {
            current.abort();
        }
        ScheduledExecutorService executor = scheduler;
        if (executor != null) {
            executor.shutdownNow();
        }
    }

    private void connect() {
        if (!running) {
            return;
        }
        long myGeneration = generation.incrementAndGet();
        stage = Stage.CONNECTING;
        phase = Phase.UNKNOWN;
        registrationIndex = 0;
        synchronized (this) {
            lastSend = CompletableFuture.completedFuture(null);
        }
        openedAt = clock.instant();
        lastReceivedAt = openedAt;

        String token;
        try {
            token = tokenProvider.token();
        } catch (RuntimeException e) {
            // 토큰 발급 실패(키 오류, IP 불일치 등). 메시지는 비밀 값 없이 안내 문구만 담고 있다.
            log.warn("키움 토큰을 받지 못해 WebSocket에 연결하지 못함: {}", e.getMessage());
            onConnectionLost(myGeneration);
            return;
        }

        httpClient.newWebSocketBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .buildAsync(URI.create(properties.webSocketUrl()), new Listener(myGeneration, token))
                .whenComplete((socket, error) -> {
                    if (error != null) {
                        log.warn("키움 WebSocket 연결 실패: {}", error.toString());
                        onConnectionLost(myGeneration);
                    }
                });
    }

    // 연결이 끊겼거나 실패했을 때. 한 연결에 대해 한 번만 처리하고 백오프 후 다시 연결한다.
    private void onConnectionLost(long failedGeneration) {
        if (!generation.compareAndSet(failedGeneration, failedGeneration + 1)) {
            return;
        }
        WebSocket old = webSocket;
        webSocket = null;
        if (old != null) {
            old.abort();
        }
        boolean wasActive = stage == Stage.ACTIVE;
        stage = Stage.IDLE;
        if (wasActive) {
            listener.onDisconnected();
        }
        if (!running) {
            return;
        }
        Duration delay = backoff.nextDelay();
        log.warn("키움 WebSocket 연결이 끊김: {}ms 뒤에 다시 연결", delay.toMillis());
        try {
            scheduler.schedule(this::connect, delay.toMillis(), TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException e) {
            // stop()으로 스케줄러가 닫힌 경우
        }
    }

    // 주기적 점검: (1) LOGIN·등록이 너무 오래 끝나지 않으면 다시 연결, (2) 장중인데 아무것도 못 받으면 다시 연결
    private void checkConnection() {
        if (!running) {
            return;
        }
        Instant now = clock.instant();
        Stage current = stage;
        if ((current == Stage.LOGIN_SENT || current == Stage.REGISTERING)
                && Duration.between(openedAt, now).compareTo(properties.setupTimeout()) > 0) {
            log.warn("키움 LOGIN·종목 등록이 {}초 안에 끝나지 않아 연결을 다시 맺음", properties.setupTimeout().toSeconds());
            onConnectionLost(generation.get());
            return;
        }
        if (current == Stage.ACTIVE && phase == Phase.OPEN && isBeforeIdleCheckEnd(now)
                && Duration.between(lastReceivedAt, now).compareTo(properties.idleTimeout()) > 0) {
            log.warn("키움에서 장중에 {}초 넘게 아무것도 받지 못해 연결을 다시 맺음", properties.idleTimeout().toSeconds());
            onConnectionLost(generation.get());
        }
    }

    private boolean isBeforeIdleCheckEnd(Instant now) {
        return now.atZone(KiwoomTokenProvider.KST).toLocalTime().isBefore(IDLE_CHECK_UNTIL);
    }

    // 메시지 하나 처리. 모르는 메시지는 건너뛴다.
    private void handleMessage(long myGeneration, WebSocket socket, String text) {
        JsonNode node;
        try {
            node = objectMapper.readTree(text);
        } catch (RuntimeException e) {
            log.warn("해석할 수 없는 키움 메시지를 건너뜀");
            return;
        }
        switch (node.path("trnm").asString("")) {
            case "PING" -> send(socket, text); // 받은 그대로 되돌려 보낸다
            case "LOGIN" -> onLoginResponse(myGeneration, socket, node);
            case "REG" -> onRegisterResponse(myGeneration, socket, node);
            case "REAL" -> onReal(node);
            default -> log.debug("처리하지 않는 키움 메시지: trnm={}", node.path("trnm").asString(""));
        }
    }

    private void onLoginResponse(long myGeneration, WebSocket socket, JsonNode node) {
        int code = node.path("return_code").asInt(-1);
        if (code != 0) {
            KiwoomException failure = KiwoomException.of(code, node.path("return_msg").asString(""));
            log.warn("키움 WebSocket LOGIN 실패: {}", failure.getMessage());
            if (failure.isTokenProblem()) {
                tokenProvider.invalidate(); // 이전 토큰을 폐기하고, 다음 연결 때 새 토큰을 발급받는다(그 전에 백오프로 대기)
            }
            onConnectionLost(myGeneration);
            return;
        }
        stage = Stage.REGISTERING;
        sendRegistration(socket, 0);
    }

    private void onRegisterResponse(long myGeneration, WebSocket socket, JsonNode node) {
        int code = node.path("return_code").asInt(-1);
        if (code != 0) {
            KiwoomException failure = KiwoomException.of(code, node.path("return_msg").asString(""));
            log.warn("키움 실시간 등록 실패: {}", failure.getMessage());
            onConnectionLost(myGeneration);
            return;
        }
        int next = ++registrationIndex;
        if (next < REGISTRATION_COUNT) {
            sendRegistration(socket, next);
            return;
        }
        stage = Stage.ACTIVE;
        // LOGIN과 종목 등록이 모두 성공해야 연결이 정상이라고 보고 재연결 대기 시간을 처음으로 되돌린다.
        // 첫 메시지(LOGIN 응답)에서 되돌리면 LOGIN·REG가 계속 실패해도 대기 시간이 안 늘어나 약 1초 간격으로 재연결한다.
        backoff.reset();
        log.info("키움 실시간 등록 완료: symbols={}", symbols.size());
        listener.onConnected(clock.instant());
    }

    private static final int REGISTRATION_COUNT = 2;

    // 0: 종목 체결(0B) 등록, 1: 장시작시간(0s) 등록. 허용 목록에 있는 TR만 등록할 수 있다.
    private void sendRegistration(WebSocket socket, int index) {
        Map<String, Object> message;
        if (index == 0) {
            KiwoomTrAllowlist.requireRealtime("0B");
            message = Map.of("trnm", "REG", "grp_no", "1", "refresh", "1",
                    "data", List.of(Map.of("item", properties.normalizedSymbols(), "type", List.of("0B"))));
        } else {
            KiwoomTrAllowlist.requireRealtime("0s");
            message = Map.of("trnm", "REG", "grp_no", "2", "refresh", "1",
                    "data", List.of(Map.of("item", List.of(""), "type", List.of("0s"))));
        }
        send(socket, objectMapper.writeValueAsString(message));
    }

    private void onReal(JsonNode node) {
        if (stage != Stage.ACTIVE) {
            return;
        }
        String operationCode = KiwoomRealtimeParser.marketOperationCode(node);
        if (operationCode != null) {
            updatePhase(operationCode);
        }
        LocalDate kstDate = LocalDate.ofInstant(clock.instant(), KiwoomTokenProvider.KST);
        for (Trade trade : KiwoomRealtimeParser.parseTrades(node, kstDate, symbols)) {
            if (phase == Phase.UNKNOWN) {
                phase = Phase.OPEN; // 장중 체결이 오면 중간에 접속했더라도 장중으로 본다
            }
            listener.onTrade(trade);
        }
    }

    // 장운영구분(215): 3 장시작만 OPEN. 장전(0)·마감 알림(2, 동시호가라 체결 없음)·마감(4, 8, 9)·시간외(a~e)는 CLOSED
    private void updatePhase(String operationCode) {
        Phase next = "3".equals(operationCode) ? Phase.OPEN : Phase.CLOSED;
        if (next != phase) {
            log.info("키움 장 운영 구분 변경: code={}, 유휴 감지={}", operationCode, next == Phase.OPEN ? "켬" : "끔");
            phase = next;
        }
    }

    // 보내는 메시지는 하나씩 순서대로(JDK WebSocket은 앞 전송이 끝나기 전에 다음을 보내면 오류가 난다)
    private synchronized void send(WebSocket socket, String text) {
        lastSend = lastSend.handle((ignored, error) -> null)
                .thenCompose(ignored -> socket.sendText(text, true));
        lastSend.whenComplete((result, error) -> {
            if (error != null) {
                log.warn("키움 WebSocket 전송 실패: {}", error.toString());
            }
        });
    }

    private final class Listener implements WebSocket.Listener {

        private final long myGeneration;
        private final String token;
        private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();

        Listener(long myGeneration, String token) {
            this.myGeneration = myGeneration;
            this.token = token;
        }

        @Override
        public void onOpen(WebSocket socket) {
            if (generation.get() != myGeneration) {
                socket.abort();
                return;
            }
            webSocket = socket;
            stage = Stage.LOGIN_SENT;
            lastReceivedAt = clock.instant();
            // 토큰이 들어 있는 메시지라서 로그에 남기지 않는다
            send(socket, objectMapper.writeValueAsString(Map.of("trnm", "LOGIN", "token", token)));
            socket.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket socket, CharSequence data, boolean last) {
            buffer.writeBytes(data.toString().getBytes(StandardCharsets.UTF_8));
            return finish(socket, last);
        }

        @Override
        public CompletionStage<?> onBinary(WebSocket socket, ByteBuffer data, boolean last) {
            byte[] chunk = new byte[data.remaining()];
            data.get(chunk);
            buffer.writeBytes(chunk);
            return finish(socket, last);
        }

        private CompletionStage<?> finish(WebSocket socket, boolean last) {
            if (last) {
                String message = buffer.toString(StandardCharsets.UTF_8);
                buffer.reset();
                received(socket, message);
            }
            socket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onPing(WebSocket socket, ByteBuffer message) {
            lastReceivedAt = clock.instant();
            socket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onPong(WebSocket socket, ByteBuffer message) {
            lastReceivedAt = clock.instant();
            socket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket socket, int statusCode, String reason) {
            log.warn("키움 WebSocket이 닫힘: code={}, reason={}", statusCode, reason);
            onConnectionLost(myGeneration);
            return null;
        }

        @Override
        public void onError(WebSocket socket, Throwable error) {
            log.warn("키움 WebSocket 오류: {}", error.toString());
            onConnectionLost(myGeneration);
        }

        private void received(WebSocket socket, String message) {
            if (generation.get() != myGeneration) {
                return;
            }
            lastReceivedAt = clock.instant();
            handleMessage(myGeneration, socket, message);
        }
    }
}
