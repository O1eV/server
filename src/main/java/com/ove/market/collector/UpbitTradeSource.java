package com.ove.market.collector;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 업비트 WebSocket(trade)에서 체결을 받는다. JDK 내장 java.net.http.WebSocket만 쓴다.
 *
 * - 구독 요청은 종목 전체를 한 번에 보낸다(새 구독 메시지는 이전 구독을 대체하기 때문).
 * - 업비트는 JSON을 binary 프레임으로 보내고 큰 메시지는 여러 프레임으로 쪼개서 보낼 수 있어 이어 붙여 해석한다.
 * - 끊기면 지수 백오프로 다시 연결하고, 주기적으로 ping을 보내며, 아무것도 못 받는 상태가 길어지면 연결을 다시 맺는다
 *   (유휴 점검은 ping과 별도 주기로 돈다).
 */
@Slf4j
public class UpbitTradeSource implements TradeSource {

    private final CollectorProperties properties;
    private final ObjectMapper objectMapper;
    private final ReconnectBackoff backoff;
    private final Clock clock;
    private final HttpClient httpClient = HttpClient.newHttpClient();

    // 연결을 새로 맺거나 잃을 때마다 올라가는 번호. 이미 버린 연결에서 늦게 오는 콜백을 구분하려고 쓴다.
    private final AtomicLong generation = new AtomicLong();

    private volatile TradeListener listener;
    private volatile ScheduledExecutorService scheduler;
    private volatile boolean running;
    private volatile WebSocket webSocket;
    private volatile boolean connected;
    private volatile boolean receivedSinceConnect;
    private volatile Instant lastReceivedAt;

    public UpbitTradeSource(CollectorProperties properties, ObjectMapper objectMapper, ReconnectBackoff backoff,
                            Clock clock) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.backoff = backoff;
        this.clock = clock;
    }

    @Override
    public void start(TradeListener listener) {
        this.listener = listener;
        this.running = true;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "upbit-trade-source");
            thread.setDaemon(true);
            return thread;
        });
        scheduler.execute(this::connect);
        long pingMillis = properties.pingInterval().toMillis();
        scheduler.scheduleWithFixedDelay(this::sendPing, pingMillis, pingMillis, TimeUnit.MILLISECONDS);
        // 유휴 점검은 ping 주기와 따로 돌린다. 같이 돌리면 감지가 최대 idleTimeout + pingInterval까지 늦어진다.
        long idleCheckMillis = properties.idleCheckInterval().toMillis();
        scheduler.scheduleWithFixedDelay(this::checkIdle, idleCheckMillis, idleCheckMillis, TimeUnit.MILLISECONDS);
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
        receivedSinceConnect = false;
        httpClient.newWebSocketBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .buildAsync(URI.create(properties.webSocketUrl()), new Listener(myGeneration))
                .whenComplete((socket, error) -> {
                    if (error != null) {
                        log.warn("업비트 WebSocket 연결 실패: {}", error.toString());
                        onConnectionLost(myGeneration);
                    }
                });
    }

    // 연결이 끊겼거나 연결에 실패했을 때. 한 연결에 대해 한 번만 처리하고 백오프 후 다시 연결한다.
    private void onConnectionLost(long failedGeneration) {
        if (!generation.compareAndSet(failedGeneration, failedGeneration + 1)) {
            return;
        }
        WebSocket old = webSocket;
        webSocket = null;
        if (old != null) {
            old.abort();
        }
        if (connected) {
            connected = false;
            listener.onDisconnected();
        }
        if (!running) {
            return;
        }
        Duration delay = backoff.nextDelay();
        log.warn("업비트 WebSocket 연결이 끊김: {}ms 뒤에 다시 연결", delay.toMillis());
        try {
            scheduler.schedule(this::connect, delay.toMillis(), TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.RejectedExecutionException e) {
            // stop()으로 스케줄러가 닫힌 경우
        }
    }

    // 업비트는 120초 동안 주고받는 게 없으면 연결을 끊으므로, 주기적으로 ping을 보내 연결을 유지한다.
    private void sendPing() {
        WebSocket current = webSocket;
        if (!running || !connected || current == null) {
            return;
        }
        current.sendPing(ByteBuffer.allocate(0));
    }

    // 연결은 열려 있는데 데이터가 멎는 경우(네트워크가 패킷을 버릴 때)가 있어서, 너무 오래 아무것도 못 받았으면
    // 죽은 연결로 보고 끊는다. pong도 수신으로 세므로 정상 연결은 ping 주기마다 갱신된다.
    private void checkIdle() {
        if (!running || !connected || webSocket == null) {
            return;
        }
        Instant last = lastReceivedAt;
        if (last != null && Duration.between(last, clock.instant()).compareTo(properties.idleTimeout()) > 0) {
            log.warn("업비트에서 {}초 넘게 아무것도 받지 못해 연결을 다시 맺음", properties.idleTimeout().toSeconds());
            onConnectionLost(generation.get());
        }
    }

    private String subscriptionMessage() {
        return objectMapper.writeValueAsString(List.of(
                java.util.Map.of("ticket", UUID.randomUUID().toString()),
                java.util.Map.of("type", "trade", "codes", properties.upperCaseSymbols(), "is_only_realtime", true),
                java.util.Map.of("format", "DEFAULT")));
    }

    // 메시지 하나를 해석해 체결이면 리스너에 넘긴다. 체결이 아니거나 깨진 메시지는 건너뛴다.
    private void handleMessage(String json) {
        try {
            JsonNode node = objectMapper.readTree(json);
            if (!"trade".equals(node.path("type").asString())
                    || "SNAPSHOT".equals(node.path("stream_type").asString())) {
                return;
            }
            BigDecimal price = node.path("trade_price").decimalValue();
            BigDecimal volume = node.path("trade_volume").decimalValue();
            long timestamp = node.path("trade_timestamp").asLong();
            String code = node.path("code").asString();
            if (code.isEmpty() || price.signum() <= 0 || volume.signum() < 0 || timestamp <= 0) {
                log.warn("필수 필드가 없거나 값이 올바르지 않은 체결 메시지를 건너뜀: {}", json);
                return;
            }
            listener.onTrade(new Trade(code, price, volume, Instant.ofEpochMilli(timestamp)));
        } catch (RuntimeException e) {
            log.warn("해석할 수 없는 메시지를 건너뜀: {}", e.toString());
        }
    }

    private final class Listener implements WebSocket.Listener {

        private final long myGeneration;
        private final ByteArrayOutputStream binaryBuffer = new ByteArrayOutputStream();
        private final StringBuilder textBuffer = new StringBuilder();

        Listener(long myGeneration) {
            this.myGeneration = myGeneration;
        }

        @Override
        public void onOpen(WebSocket socket) {
            if (generation.get() != myGeneration) {
                socket.abort();
                return;
            }
            webSocket = socket;
            lastReceivedAt = clock.instant();
            socket.sendText(subscriptionMessage(), true).whenComplete((result, error) -> {
                if (error != null) {
                    log.warn("업비트 구독 요청 전송 실패: {}", error.toString());
                    onConnectionLost(myGeneration);
                    return;
                }
                if (generation.get() == myGeneration) {
                    connected = true;
                    listener.onConnected(clock.instant());
                }
            });
            socket.request(1);
        }

        @Override
        public CompletionStage<?> onBinary(WebSocket socket, ByteBuffer data, boolean last) {
            byte[] chunk = new byte[data.remaining()];
            data.get(chunk);
            binaryBuffer.writeBytes(chunk);
            if (last) {
                String message = binaryBuffer.toString(StandardCharsets.UTF_8);
                binaryBuffer.reset();
                received(message);
            }
            socket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onText(WebSocket socket, CharSequence data, boolean last) {
            textBuffer.append(data);
            if (last) {
                String message = textBuffer.toString();
                textBuffer.setLength(0);
                received(message);
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
            log.warn("업비트 WebSocket이 닫힘: code={}, reason={}", statusCode, reason);
            onConnectionLost(myGeneration);
            return null;
        }

        @Override
        public void onError(WebSocket socket, Throwable error) {
            log.warn("업비트 WebSocket 오류: {}", error.toString());
            onConnectionLost(myGeneration);
        }

        private void received(String message) {
            if (generation.get() != myGeneration) {
                return;
            }
            lastReceivedAt = clock.instant();
            // 첫 메시지를 받아야 연결이 정상이라고 보고 재연결 대기 시간을 처음으로 되돌린다.
            if (!receivedSinceConnect) {
                receivedSinceConnect = true;
                backoff.reset();
            }
            handleMessage(message);
        }
    }
}
