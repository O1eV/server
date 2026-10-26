package com.ove.realtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;

import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.connection.DefaultMessage;
import org.springframework.messaging.converter.JacksonJsonMessageConverter;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;

import com.ove.auth.repository.TokenRedisRepository;
import com.ove.global.security.IssuedToken;
import com.ove.global.security.JwtProperties;
import com.ove.global.security.JwtProvider;
import com.ove.market.client.UpbitClient;
import com.ove.realtime.service.PriceRelay;
import com.ove.realtime.service.RealtimePublisher;
import com.ove.user.domain.User;
import com.ove.user.repository.UserRepository;
import com.ove.watchlist.domain.Watchlist;
import com.ove.watchlist.repository.WatchlistRepository;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class RealtimeWebSocketTest {

    @Value("${local.server.port}")
    private int port;

    @Autowired
    private RealtimePublisher realtimePublisher;

    @Autowired
    private PriceRelay priceRelay;

    @Autowired
    private JwtProvider jwtProvider;

    @Autowired
    private JwtProperties jwtProperties;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private WatchlistRepository watchlistRepository;

    @MockitoBean
    private UpbitClient upbitClient;

    @MockitoBean
    private TokenRedisRepository tokenRedisRepository;

    private WebSocketStompClient stompClient;

    // 테스트마다 STOMP 클라이언트를 새로 만들 때 사용
    @BeforeEach
    void setUp() {
        stompClient = new WebSocketStompClient(new StandardWebSocketClient());
        stompClient.setMessageConverter(new JacksonJsonMessageConverter());
    }

    // 테스트가 끝날 때마다 만든 데이터를 지울 때 사용
    @AfterEach
    void cleanUp() {
        watchlistRepository.deleteAllInBatch();
        userRepository.deleteAllInBatch();
    }

    @Test
    @DisplayName("시세: 로그인 없이 구독하면 Redis로 들어온 현재가를 받는다")
    void price() throws Exception {
        StompSession session = connect(null, new ErrorCatcher());
        BlockingQueue<Map<String, Object>> prices = subscribe(session, "/topic/prices/KRW-BTC");

        Map<String, Object> received = waitFor(prices, () -> priceRelay.onMessage(btcTick(), null));

        assertThat(received.get("symbol")).isEqualTo("KRW-BTC");
        assertThat(((Number) received.get("price")).longValue()).isEqualTo(98500000L);
        assertThat(received.get("time")).isEqualTo("2026-10-19T01:00:00Z");
    }

    @Test
    @DisplayName("구독 제한: 로그인 없이 신호를 구독하면 UNAUTHORIZED, 없는 종목은 MARKET_NOT_FOUND")
    void subscribeError() throws Exception {
        ErrorCatcher signalError = new ErrorCatcher();
        subscribe(connect(null, signalError), "/topic/signals/KRW-BTC");
        assertThat(signalError.message()).isEqualTo("UNAUTHORIZED");

        ErrorCatcher marketError = new ErrorCatcher();
        subscribe(connect(null, marketError), "/topic/prices/KRW-DOGE");
        assertThat(marketError.message()).isEqualTo("MARKET_NOT_FOUND");
    }

    @Test
    @DisplayName("접속: 잘못된 토큰으로 접속하면 UNAUTHORIZED")
    void badToken() throws Exception {
        ErrorCatcher error = new ErrorCatcher();
        connectAsync("forged.token.value", error);
        assertThat(error.message()).isEqualTo("UNAUTHORIZED");
    }

    @Test
    @DisplayName("신호 변경: 구독자는 신호를 받고, 그 종목을 관심 종목에 넣은 사람만 알림을 받는다")
    void signalAndAlert() throws Exception {
        User me = saveUser("me@ove.com", "나");
        User other = saveUser("other@ove.com", "남");
        watch(me, "KRW-BTC");

        StompSession mySession = connect(tokenOf(me), new ErrorCatcher());
        BlockingQueue<Map<String, Object>> myAlerts = subscribe(mySession, "/user/queue/alerts");
        BlockingQueue<Map<String, Object>> mySignals = subscribe(mySession, "/topic/signals/KRW-BTC");

        StompSession otherSession = connect(tokenOf(other), new ErrorCatcher());
        BlockingQueue<Map<String, Object>> otherAlerts = subscribe(otherSession, "/user/queue/alerts");

        Map<String, Object> signal = waitFor(mySignals, () -> realtimePublisher.publishSignalChange(
                "KRW-BTC", "HOLD", "BUY", new BigDecimal("0.71"), false));
        assertThat(signal.get("signal")).isEqualTo("BUY");
        assertThat(((Number) signal.get("confidence")).doubleValue()).isEqualTo(0.71);
        assertThat(signal.get("stale")).isEqualTo(false);

        Map<String, Object> alert = myAlerts.poll(3, TimeUnit.SECONDS);
        assertThat(alert).isNotNull();
        assertThat(alert.get("type")).isEqualTo("SIGNAL_CHANGED");
        assertThat(alert.get("from")).isEqualTo("HOLD");
        assertThat(alert.get("to")).isEqualTo("BUY");

        assertThat(otherAlerts.poll(500, TimeUnit.MILLISECONDS)).isNull();
    }

    @Test
    @DisplayName("SEND 막기: 클라이언트가 /topic으로 직접 보내면 FORBIDDEN이고, 다른 구독자에게 가지 않는다")
    void sendBlocked() throws Exception {
        StompSession listener = connect(null, new ErrorCatcher());
        BlockingQueue<Map<String, Object>> prices = subscribe(listener, "/topic/prices/KRW-BTC");
        waitFor(prices, () -> priceRelay.onMessage(btcTick(), null));
        drain(prices);

        ErrorCatcher senderError = new ErrorCatcher();
        StompSession sender = connect(null, senderError);
        sender.send("/topic/prices/KRW-BTC", Map.of("symbol", "KRW-BTC", "price", 1));

        assertThat(senderError.message()).isEqualTo("FORBIDDEN");
        assertThat(prices.poll(500, TimeUnit.MILLISECONDS)).isNull();
    }

    @Test
    @DisplayName("로그아웃: 접속 뒤 토큰이 블랙리스트에 오르면 알림이 끊기고, 새로 구독하면 UNAUTHORIZED")
    void logoutStopsAlerts() throws Exception {
        User me = saveUser("me@ove.com", "나");
        User other = saveUser("other@ove.com", "남");
        watch(me, "KRW-BTC");
        watch(other, "KRW-BTC");
        IssuedToken myToken = jwtProvider.createAccessToken(me.getId());

        ErrorCatcher myError = new ErrorCatcher();
        StompSession mySession = connect(myToken.token(), myError);
        BlockingQueue<Map<String, Object>> myAlerts = subscribe(mySession, "/user/queue/alerts");
        BlockingQueue<Map<String, Object>> otherAlerts =
                subscribe(connect(tokenOf(other), new ErrorCatcher()), "/user/queue/alerts");
        waitFor(myAlerts, this::publishBtcSignal);
        waitFor(otherAlerts, this::publishBtcSignal);
        drain(myAlerts, otherAlerts);

        given(tokenRedisRepository.isRevoked(myToken.jti(), me.getId())).willReturn(true);
        publishBtcSignal();

        assertThat(otherAlerts.poll(3, TimeUnit.SECONDS)).isNotNull();
        assertThat(myAlerts.poll(500, TimeUnit.MILLISECONDS)).isNull();

        subscribe(mySession, "/topic/signals/KRW-BTC");
        assertThat(myError.message()).isEqualTo("UNAUTHORIZED");
    }

    @Test
    @DisplayName("탈퇴한 회원: 탈퇴 표시가 있는 토큰으로는 접속할 수 없다 (UNAUTHORIZED)")
    void withdrawnUserCannotConnect() throws Exception {
        User me = saveUser("me@ove.com", "나");
        IssuedToken myToken = jwtProvider.createAccessToken(me.getId());
        given(tokenRedisRepository.isRevoked(myToken.jti(), me.getId())).willReturn(true);

        ErrorCatcher error = new ErrorCatcher();
        connectAsync(myToken.token(), error);

        assertThat(error.message()).isEqualTo("UNAUTHORIZED");
        // 블랙리스트만이 아니라 회원 ID까지 함께 확인한다
        verify(tokenRedisRepository, atLeastOnce()).isRevoked(myToken.jti(), me.getId());
    }

    @Test
    @DisplayName("탈퇴: 접속 뒤 회원이 탈퇴하면 알림이 끊기고, 새로 구독하면 UNAUTHORIZED (다른 회원은 계속 받는다)")
    void withdrawalStopsAlerts() throws Exception {
        User me = saveUser("me@ove.com", "나");
        User other = saveUser("other@ove.com", "남");
        watch(me, "KRW-BTC");
        watch(other, "KRW-BTC");

        ErrorCatcher myError = new ErrorCatcher();
        StompSession mySession = connect(tokenOf(me), myError);
        BlockingQueue<Map<String, Object>> myAlerts = subscribe(mySession, "/user/queue/alerts");
        BlockingQueue<Map<String, Object>> otherAlerts =
                subscribe(connect(tokenOf(other), new ErrorCatcher()), "/user/queue/alerts");
        waitFor(myAlerts, this::publishBtcSignal);
        waitFor(otherAlerts, this::publishBtcSignal);
        drain(myAlerts, otherAlerts);

        // 탈퇴하면 Redis에 withdrawn:{userId}가 생기고 isRevoked가 true가 된다 (jti는 블랙리스트에 없다)
        given(tokenRedisRepository.isRevoked(anyString(), eq(me.getId()))).willReturn(true);
        publishBtcSignal();

        assertThat(otherAlerts.poll(3, TimeUnit.SECONDS)).isNotNull();
        assertThat(myAlerts.poll(500, TimeUnit.MILLISECONDS)).isNull();

        subscribe(mySession, "/topic/signals/KRW-BTC");
        assertThat(myError.message()).isEqualTo("UNAUTHORIZED");
    }

    @Test
    @DisplayName("토큰 만료: 접속 뒤 access 토큰이 만료되면 알림이 끊긴다")
    void expiredTokenStopsAlerts() throws Exception {
        User me = saveUser("me@ove.com", "나");
        User other = saveUser("other@ove.com", "남");
        watch(me, "KRW-BTC");
        watch(other, "KRW-BTC");
        JwtProvider shortLived = new JwtProvider(new JwtProperties(jwtProperties.secret(),
                Duration.ofSeconds(3), jwtProperties.refreshTokenExpiration()));
        IssuedToken myToken = shortLived.createAccessToken(me.getId());

        BlockingQueue<Map<String, Object>> myAlerts =
                subscribe(connect(myToken.token(), new ErrorCatcher()), "/user/queue/alerts");
        BlockingQueue<Map<String, Object>> otherAlerts =
                subscribe(connect(tokenOf(other), new ErrorCatcher()), "/user/queue/alerts");
        waitFor(myAlerts, this::publishBtcSignal);
        waitFor(otherAlerts, this::publishBtcSignal);
        drain(myAlerts, otherAlerts);

        long untilExpired = Duration.between(Instant.now(), myToken.expiresAt()).toMillis() + 300;
        if (untilExpired > 0) {
            Thread.sleep(untilExpired);
        }
        publishBtcSignal();

        assertThat(otherAlerts.poll(3, TimeUnit.SECONDS)).isNotNull();
        assertThat(myAlerts.poll(500, TimeUnit.MILLISECONDS)).isNull();
    }

    // 서버에 STOMP로 접속하고 연결이 끝날 때까지 기다릴 때 사용
    private StompSession connect(String token, ErrorCatcher handler) throws Exception {
        return connectAsync(token, handler).get(5, TimeUnit.SECONDS);
    }

    // 서버에 STOMP 접속을 시작만 할 때 사용 (잘못된 토큰처럼 연결이 안 되는 경우)
    private CompletableFuture<StompSession> connectAsync(String token, ErrorCatcher handler) {
        StompHeaders headers = new StompHeaders();
        if (token != null) {
            headers.add("Authorization", "Bearer " + token);
        }
        return stompClient.connectAsync("ws://localhost:" + port + "/ws", new WebSocketHttpHeaders(), headers, handler);
    }

    // 주소를 구독하고 받은 메시지를 줄 세워 둘 때 사용
    private BlockingQueue<Map<String, Object>> subscribe(StompSession session, String destination) {
        BlockingQueue<Map<String, Object>> queue = new LinkedBlockingQueue<>();
        session.subscribe(destination, new StompFrameHandler() {
            @Override
            public Type getPayloadType(StompHeaders headers) {
                return Map.class;
            }

            @Override
            @SuppressWarnings("unchecked")
            public void handleFrame(StompHeaders headers, Object payload) {
                queue.add((Map<String, Object>) payload);
            }
        });
        return queue;
    }

    // 구독이 서버에 등록될 때까지 보내기를 반복하다가 첫 메시지를 받을 때 사용
    private Map<String, Object> waitFor(BlockingQueue<Map<String, Object>> queue, Runnable send) throws Exception {
        for (int i = 0; i < 20; i++) {
            send.run();
            Map<String, Object> message = queue.poll(250, TimeUnit.MILLISECONDS);
            if (message != null) {
                return message;
            }
        }
        throw new AssertionError("메시지를 받지 못했습니다.");
    }

    // 이미 받은 메시지를 잠깐 기다렸다가 비울 때 사용 (늦게 도착하는 메시지까지)
    @SafeVarargs
    private void drain(BlockingQueue<Map<String, Object>>... queues) throws InterruptedException {
        Thread.sleep(300);
        for (BlockingQueue<Map<String, Object>> queue : queues) {
            queue.clear();
        }
    }

    // 비트코인 신호가 HOLD → BUY로 바뀌었다고 보낼 때 사용
    private void publishBtcSignal() {
        realtimePublisher.publishSignalChange("KRW-BTC", "HOLD", "BUY", new BigDecimal("0.71"), false);
    }

    // 사용자의 관심 종목에 종목을 넣을 때 사용
    private void watch(User user, String symbol) {
        watchlistRepository.save(Watchlist.builder().userId(user.getId()).symbol(symbol).build());
    }

    // 수집기가 Redis로 보내는 비트코인 현재가 메시지를 만들 때 사용
    private DefaultMessage btcTick() {
        return redisTick("{\"symbol\":\"KRW-BTC\",\"price\":98500000,\"tradeTimestamp\":\"2026-10-19T01:00:00Z\","
                + "\"updatedAt\":\"2026-10-19T01:00:00.100Z\"}");
    }

    // 수집기가 Redis로 보내는 것과 같은 모양의 메시지를 만들 때 사용
    private DefaultMessage redisTick(String json) {
        return new DefaultMessage("prices:KRW-BTC".getBytes(StandardCharsets.UTF_8),
                json.getBytes(StandardCharsets.UTF_8));
    }

    // 테스트용 사용자를 저장할 때 사용
    private User saveUser(String email, String nickname) {
        return userRepository.save(User.builder()
                .email(email)
                .password("encoded-password")
                .nickname(nickname)
                .build());
    }

    // 사용자의 access 토큰을 만들 때 사용
    private String tokenOf(User user) {
        return jwtProvider.createAccessToken(user.getId()).token();
    }

    private static class ErrorCatcher extends StompSessionHandlerAdapter {

        private final CompletableFuture<String> error = new CompletableFuture<>();

        // 서버가 보낸 ERROR 프레임의 message를 기억할 때 사용
        @Override
        public void handleFrame(StompHeaders headers, Object payload) {
            error.complete(headers.getFirst("message"));
        }

        // ERROR 프레임을 처리하다 예외가 나도 message를 기억할 때 사용
        @Override
        public void handleException(StompSession session, StompCommand command, StompHeaders headers,
                                    byte[] payload, Throwable exception) {
            if (command == StompCommand.ERROR) {
                error.complete(headers.getFirst("message"));
            }
        }

        // 받은 ERROR message를 꺼낼 때 사용 (3초 안에 안 오면 실패)
        String message() throws Exception {
            return error.get(3, TimeUnit.SECONDS);
        }
    }
}
