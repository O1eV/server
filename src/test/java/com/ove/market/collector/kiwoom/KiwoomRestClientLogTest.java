package com.ove.market.collector.kiwoom;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import com.ove.support.FakeKiwoomRestServer;
import com.ove.support.FakeKiwoomRestServer.Reply;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import tools.jackson.databind.ObjectMapper;

/**
 * RestClient의 DEBUG 로그(요청 본문 출력 등)를 켜도 App Key·Secret·토큰이 로그에 남지 않는지 확인한다.
 */
class KiwoomRestClientLogTest {

    private static final String APP_KEY = "TEST-APP-KEY-not-real";
    private static final String APP_SECRET = "TEST-APP-SECRET-not-real";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private FakeKiwoomRestServer server;
    private Logger rootLogger;
    private Level originalLevel;
    private ListAppender<ILoggingEvent> logs;

    @BeforeEach
    void setUp() throws Exception {
        server = new FakeKiwoomRestServer();
        server.handle("/oauth2/token", request ->
                Reply.ok(FakeKiwoomRestServer.tokenJson("TEST-TOKEN-1", "20261022160000")));
        server.handle("/oauth2/revoke", request -> Reply.ok("{\"return_code\":0,\"return_msg\":\"정상\"}"));

        // 모든 로거를 DEBUG(RestClient·HTTP 클라이언트 포함)로 켜고 로그를 모은다
        rootLogger = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        originalLevel = rootLogger.getLevel();
        rootLogger.setLevel(Level.TRACE);
        logs = new ListAppender<>();
        logs.start();
        rootLogger.addAppender(logs);
    }

    @AfterEach
    void tearDown() {
        rootLogger.detachAppender(logs);
        rootLogger.setLevel(originalLevel);
        server.close();
    }

    @Test
    @DisplayName("DEBUG 로그를 켜도 토큰 발급·폐기 요청의 App Key·Secret·토큰이 로그에 남지 않는다")
    void secretsNeverLoggedAtDebug() {
        KiwoomProperties properties = new KiwoomProperties(true, List.of("005930"), APP_KEY, APP_SECRET, server.url(),
                "ws://127.0.0.1:1", Duration.ofMinutes(10), Duration.ofSeconds(120), Duration.ofSeconds(15),
                Duration.ZERO, Duration.ofSeconds(1), Duration.ofSeconds(2), false, null, "1");
        KiwoomRestClient restClient = new KiwoomRestClient(server.url(), Duration.ofSeconds(1), Duration.ofSeconds(2),
                Duration.ZERO, objectMapper);
        KiwoomTokenProvider provider = new KiwoomTokenProvider(restClient, properties, Clock.systemUTC());

        provider.token();
        provider.close();

        List<String> lines = new ArrayList<>();
        for (ILoggingEvent event : new ArrayList<>(logs.list)) {
            lines.add(event.getFormattedMessage());
            if (event.getThrowableProxy() != null) {
                lines.add(event.getThrowableProxy().getMessage());
            }
        }
        assertThat(lines).isNotEmpty();
        assertThat(String.join("\n", lines)).doesNotContain(APP_KEY).doesNotContain(APP_SECRET)
                .doesNotContain("TEST-TOKEN");
    }

    @Test
    @DisplayName("본문을 직접 직렬화해도 Content-Length로 보내고(chunked 아님) 서버가 같은 JSON을 받는다")
    void sendsJsonWithContentLength() throws Exception {
        KiwoomRestClient restClient = new KiwoomRestClient(server.url(), Duration.ofSeconds(1), Duration.ofSeconds(2),
                Duration.ZERO, objectMapper);

        restClient.post("au10001", "/oauth2/token", null,
                java.util.Map.of("grant_type", "client_credentials", "appkey", APP_KEY, "secretkey", APP_SECRET), null);

        FakeKiwoomRestServer.Received request = server.requestsTo("/oauth2/token").get(0);
        assertThat(request.headers()).doesNotContainKey("transfer-encoding");
        assertThat(request.headers().get("content-length"))
                .isEqualTo(String.valueOf(request.body().getBytes(StandardCharsets.UTF_8).length));
        assertThat(objectMapper.readTree(request.body()).get("appkey").asString()).isEqualTo(APP_KEY);
        assertThat(request.headers().get("content-type")).startsWith("application/json");
    }
}
