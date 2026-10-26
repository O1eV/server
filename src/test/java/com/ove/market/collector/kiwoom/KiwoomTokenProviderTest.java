package com.ove.market.collector.kiwoom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.ove.support.FakeKiwoomRestServer;
import com.ove.support.FakeKiwoomRestServer.Reply;
import com.ove.support.MutableClock;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 가짜 키움 REST 서버로 토큰 발급·폐기·만료 임박 재발급·오류 코드 해석을 확인한다. 실제 키움에는 연결하지 않는다.
 * 키와 토큰은 모두 테스트용 가짜 값이다.
 */
class KiwoomTokenProviderTest {

    private static final String APP_KEY = "TEST-APP-KEY-not-real";
    private static final String APP_SECRET = "TEST-APP-SECRET-not-real";
    // 2026-10-21 16:00:00 KST = 07:00:00 UTC
    private static final String EXPIRES_DT = "20261021160000";
    private static final Instant EXPIRES_AT = Instant.parse("2026-10-21T07:00:00Z");

    private final ObjectMapper objectMapper = new ObjectMapper();
    private FakeKiwoomRestServer server;
    private MutableClock clock;
    private final AtomicInteger issued = new AtomicInteger();

    @BeforeEach
    void setUp() throws Exception {
        server = new FakeKiwoomRestServer();
        clock = new MutableClock(EXPIRES_AT.minus(Duration.ofHours(8)));
        // 발급할 때마다 다른 토큰을 준다
        server.handle("/oauth2/token", request ->
                Reply.ok(FakeKiwoomRestServer.tokenJson("TEST-TOKEN-" + issued.incrementAndGet(), EXPIRES_DT)));
        server.handle("/oauth2/revoke", request -> Reply.ok("{\"return_code\":0,\"return_msg\":\"정상\"}"));
    }

    @AfterEach
    void tearDown() {
        server.close();
    }

    @Test
    @DisplayName("서버가 토큰을 거부해 invalidate하면 이전 토큰을 폐기(au10002)하고 다음에 새로 발급받는다")
    void invalidateRevokesOldTokenAndReissues() {
        KiwoomTokenProvider provider = provider();
        provider.token();

        provider.invalidate();

        assertThat(server.requestsTo("/oauth2/revoke")).hasSize(1);
        assertThat(server.requestsTo("/oauth2/revoke").get(0).body()).contains("TEST-TOKEN-1");
        assertThat(provider.token()).isEqualTo("TEST-TOKEN-2");
    }

    @Test
    @DisplayName("발급받은 토큰이 없으면 invalidate는 아무것도 호출하지 않고, 폐기가 실패해도 invalidate는 성공한다")
    void invalidateWithoutTokenOrWithRevokeFailure() {
        KiwoomTokenProvider provider = provider();
        provider.invalidate();
        assertThat(server.requestsTo("/oauth2/revoke")).isEmpty();

        provider.token();
        server.handle("/oauth2/revoke", request -> Reply.ok("{\"return_code\":8005,\"return_msg\":\"invalid\"}"));
        provider.invalidate();
        assertThat(provider.token()).isEqualTo("TEST-TOKEN-2");
    }

    @Test
    @DisplayName("토큰 발급 요청 형식: POST /oauth2/token, api-id au10001, grant_type·appkey·secretkey 본문, Authorization 없음")
    void issueRequestFormat() throws Exception {
        String token = provider().token();

        assertThat(token).isEqualTo("TEST-TOKEN-1");
        FakeKiwoomRestServer.Received request = server.requestsTo("/oauth2/token").get(0);
        assertThat(request.headers()).containsEntry("api-id", "au10001").doesNotContainKey("authorization");
        assertThat(request.headers().get("content-type")).startsWith("application/json");
        JsonNode body = objectMapper.readTree(request.body());
        assertThat(body.get("grant_type").asString()).isEqualTo("client_credentials");
        assertThat(body.get("appkey").asString()).isEqualTo(APP_KEY);
        assertThat(body.get("secretkey").asString()).isEqualTo(APP_SECRET);
    }

    @Test
    @DisplayName("발급받은 토큰은 만료 전까지 재사용한다 (expires_dt는 KST로 해석)")
    void reusesTokenUntilExpiry() {
        KiwoomTokenProvider provider = provider();

        provider.token();
        clock.set(EXPIRES_AT.minus(Duration.ofMinutes(11))); // 만료 11분 전: 여유(10분)보다 이르다
        provider.token();
        provider.token();

        assertThat(server.requestsTo("/oauth2/token")).hasSize(1);
    }

    @Test
    @DisplayName("만료가 임박(여유 10분 이내)하면 다음 사용 때 새로 발급받는다")
    void reissuesNearExpiry() {
        KiwoomTokenProvider provider = provider();
        assertThat(provider.token()).isEqualTo("TEST-TOKEN-1");

        clock.set(EXPIRES_AT.minus(Duration.ofMinutes(9)));
        assertThat(provider.token()).isEqualTo("TEST-TOKEN-2");

        // 새 토큰의 만료(같은 값의 가짜 응답)가 아직 임박했으니 또 발급되지만, 만료 전에 충분히 먼 시각이면 재사용
        clock.set(EXPIRES_AT.minus(Duration.ofHours(8)));
        int before = server.requestsTo("/oauth2/token").size();
        provider.token();
        assertThat(server.requestsTo("/oauth2/token")).hasSize(before);
    }

    @Test
    @DisplayName("invalidate하면 다음 사용 때 새 토큰을 발급받는다 (서버가 토큰을 거부했을 때)")
    void invalidateForcesReissue() {
        KiwoomTokenProvider provider = provider();
        provider.token();

        provider.invalidate();

        assertThat(provider.token()).isEqualTo("TEST-TOKEN-2");
    }

    @Test
    @DisplayName("오류 8010(발급 IP와 요청 IP 불일치)은 IP를 확인하라는 읽기 쉬운 예외로 바뀐다")
    void mapsError8010() {
        server.handle("/oauth2/token", request -> Reply.ok(
                "{\"return_code\":8010,\"return_msg\":\"Token을 발급받은 IP와 서비스를 요청한 IP가 동일하지 않습니다\"}"));

        assertThatThrownBy(() -> provider().token())
                .isInstanceOfSatisfying(KiwoomException.class, e -> {
                    assertThat(e.getCode()).isEqualTo(8010);
                    assertThat(e.getMessage()).contains("8010").contains("IP").contains("VPN");
                    assertThat(e.isTokenProblem()).isTrue();
                });
    }

    @Test
    @DisplayName("오류 8030·8031(실전/모의 혼용)과 8001(키 검증 실패)을 구분해서 안내한다")
    void mapsRealMockAndKeyErrors() {
        assertThat(errorFor(8030).getMessage()).contains("8030").contains("실전/모의").contains("App Key");
        assertThat(errorFor(8031).getMessage()).contains("8031").contains("실전/모의").contains("토큰");
        assertThat(errorFor(8001).getMessage()).contains("8001").contains("KIWOOM_APP_KEY");
        assertThat(errorFor(8030).isTokenProblem()).isFalse();
    }

    @Test
    @DisplayName("HTTP 오류 상태로 와도 본문의 return_code를 읽고, 본문이 없으면 HTTP 상태로 알린다")
    void mapsHttpErrorStatus() {
        server.handle("/oauth2/token", request -> Reply.of(400,
                "{\"return_code\":8002,\"return_msg\":\"검증 실패\"}"));
        assertThatThrownBy(() -> provider().token())
                .isInstanceOfSatisfying(KiwoomException.class, e -> assertThat(e.getCode()).isEqualTo(8002));

        server.handle("/oauth2/token", request -> Reply.of(502, "<html>bad gateway</html>"));
        assertThatThrownBy(() -> provider().token())
                .isInstanceOfSatisfying(KiwoomException.class, e -> {
                    assertThat(e.getCode()).isNull();
                    assertThat(e.getMessage()).contains("HTTP 502");
                });
    }

    @Test
    @DisplayName("예외 메시지에는 App Key·Secret·토큰 값이 들어가지 않는다")
    void errorsDoNotLeakSecrets() {
        server.handle("/oauth2/token", request -> Reply.ok("{\"return_code\":8001,\"return_msg\":\"검증 실패\"}"));

        assertThatThrownBy(() -> provider().token())
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain(APP_KEY).doesNotContain(APP_SECRET));
    }

    @Test
    @DisplayName("token이 없거나 expires_dt 형식이 이상한 응답은 예외로 처리한다")
    void rejectsMalformedResponses() {
        server.handle("/oauth2/token", request -> Reply.ok("{\"return_code\":0,\"expires_dt\":\"" + EXPIRES_DT + "\"}"));
        assertThatThrownBy(() -> provider().token()).isInstanceOf(KiwoomException.class).hasMessageContaining("token");

        server.handle("/oauth2/token", request -> Reply.ok(
                "{\"return_code\":0,\"token\":\"T\",\"expires_dt\":\"2026-10-21 16:00\"}"));
        assertThatThrownBy(() -> provider().token()).isInstanceOf(KiwoomException.class).hasMessageContaining("expires_dt");
    }

    @Test
    @DisplayName("서버에 연결할 수 없으면 KiwoomException")
    void connectionFailure() throws Exception {
        String url = server.url();
        server.close();

        KiwoomRestClient restClient = new KiwoomRestClient(url, Duration.ofMillis(300), Duration.ofMillis(300),
                Duration.ZERO, objectMapper);
        assertThatThrownBy(() -> new KiwoomTokenProvider(restClient, properties(), clock).token())
                .isInstanceOf(KiwoomException.class).hasMessageContaining("연결하지 못했습니다");
    }

    @Test
    @DisplayName("close하면 발급받은 토큰을 폐기(au10002)하고, 폐기 실패는 무시한다")
    void closeRevokesToken() throws Exception {
        KiwoomTokenProvider provider = provider();
        provider.token();

        provider.close();

        FakeKiwoomRestServer.Received request = server.requestsTo("/oauth2/revoke").get(0);
        assertThat(request.headers()).containsEntry("api-id", "au10002");
        JsonNode body = objectMapper.readTree(request.body());
        assertThat(body.get("token").asString()).isEqualTo("TEST-TOKEN-1");
        assertThat(body.get("appkey").asString()).isEqualTo(APP_KEY);
        assertThat(body.get("secretkey").asString()).isEqualTo(APP_SECRET);

        // 폐기 뒤에는 새로 발급받고, 폐기 요청이 실패해도 close는 예외를 내지 않는다
        server.handle("/oauth2/revoke", r -> Reply.of(500, "boom"));
        provider.token();
        provider.close();
        provider.close(); // 토큰이 없으면 아무것도 하지 않는다
        assertThat(server.requestsTo("/oauth2/revoke")).hasSize(2);
    }

    @Test
    @DisplayName("토큰을 발급받은 적이 없으면 close해도 폐기 요청을 보내지 않는다")
    void closeWithoutTokenDoesNothing() {
        provider().close();

        assertThat(server.requests()).isEmpty();
    }

    private KiwoomException errorFor(int code) {
        server.handle("/oauth2/token", request -> Reply.ok("{\"return_code\":" + code + ",\"return_msg\":\"x\"}"));
        try {
            provider().token();
        } catch (KiwoomException e) {
            return e;
        }
        throw new AssertionError("예외가 나야 함: " + code);
    }

    private KiwoomTokenProvider provider() {
        KiwoomRestClient restClient = new KiwoomRestClient(server.url(), Duration.ofSeconds(1),
                Duration.ofSeconds(2), Duration.ZERO, objectMapper);
        return new KiwoomTokenProvider(restClient, properties(), clock);
    }

    private KiwoomProperties properties() {
        return new KiwoomProperties(true, java.util.List.of("005930"), APP_KEY, APP_SECRET, server.url(),
                "wss://unused", Duration.ofMinutes(10), Duration.ofSeconds(120), Duration.ofSeconds(15),
                Duration.ZERO, Duration.ofSeconds(1), Duration.ofSeconds(2), false, null, "1");
    }
}
