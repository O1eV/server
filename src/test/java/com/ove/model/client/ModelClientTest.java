package com.ove.model.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.ove.global.exception.BusinessException;
import com.ove.global.exception.ErrorCode;
import com.ove.market.collector.MinuteCandle;
import com.ove.model.config.ModelProperties;
import com.ove.support.Await;
import com.ove.support.FakeKiwoomRestServer;
import com.ove.support.FakeKiwoomRestServer.Reply;

/** 가짜 모델 서버(JDK HttpServer)로 확인하는 ModelClient. 실제 모델·키는 쓰지 않는다. */
class ModelClientTest {

    private static final String OK_BODY = "{\"decision\":\"BUY\",\"buy\":0.7,\"sell\":0.1,\"hold\":0.2,\"confidence\":0.7}";
    private static final Instant T0 = Instant.parse("2026-10-26T00:00:00Z");

    FakeKiwoomRestServer server;
    ModelClient client;

    @BeforeEach
    void setUp() throws Exception {
        server = new FakeKiwoomRestServer();
        client = new ModelClient(new ModelProperties(true, server.url(), "test-not-a-real-key", Duration.ofMillis(500), 30));
    }

    @AfterEach
    void tearDown() {
        server.close();
    }

    private List<MinuteCandle> candles(int count) {
        List<MinuteCandle> list = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            list.add(new MinuteCandle("KRW-BTC", T0.plusSeconds(60L * i), new BigDecimal("100"), new BigDecimal("110"),
                    new BigDecimal("90"), new BigDecimal("105"), new BigDecimal("1.5")));
        }
        return list;
    }

    @Test
    @DisplayName("정상: 캔들 배열을 X-API-KEY와 함께 보내고 결정을 받는다")
    void predicts() {
        server.handle("/api/v1/predict", request -> Reply.ok(OK_BODY));

        ModelDecision decision = client.predict(candles(15));

        assertThat(decision.decision()).isEqualTo("BUY");
        assertThat(decision.buy()).isEqualTo(0.7);
        assertThat(decision.confidence()).isEqualTo(0.7);
        var request = server.requestsTo("/api/v1/predict").get(0);
        assertThat(request.headers().get("x-api-key")).isEqualTo("test-not-a-real-key");
        assertThat(request.body()).startsWith("[{").contains("\"bar_time\":\"2026-10-26T00:00:00Z\"")
                .contains("\"open\"", "\"high\"", "\"low\"", "\"close\"", "\"volume\"");
        assertThat(request.body().split("bar_time", -1)).hasSize(16); // 캔들 15개, 오래된 것부터
    }

    @Test
    @DisplayName("5xx: 한 번 다시 시도하고 그래도 실패하면 MODEL_UNAVAILABLE")
    void serverErrorRetriesOnce() {
        server.handle("/api/v1/predict", request -> Reply.of(500, "{}"));

        assertThatThrownBy(() -> client.predict(candles(15))).isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.MODEL_UNAVAILABLE));
        assertThat(server.requestsTo("/api/v1/predict")).hasSize(2);
    }

    @Test
    @DisplayName("첫 호출이 실패해도 재시도에서 성공하면 결정을 돌려준다")
    void retrySucceeds() {
        AtomicInteger calls = new AtomicInteger();
        server.handle("/api/v1/predict", request -> calls.incrementAndGet() == 1 ? Reply.of(503, "{}") : Reply.ok(OK_BODY));

        assertThat(client.predict(candles(15)).decision()).isEqualTo("BUY");
        assertThat(server.requestsTo("/api/v1/predict")).hasSize(2);
    }

    @Test
    @DisplayName("500ms 안에 응답이 없으면 타임아웃으로 보고 한 번 다시 시도한 뒤 MODEL_UNAVAILABLE")
    void timeoutRetriesOnce() {
        server.handle("/api/v1/predict", request -> {
            try {
                Thread.sleep(1200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return Reply.ok(OK_BODY);
        });

        assertThatThrownBy(() -> client.predict(candles(15))).isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.MODEL_UNAVAILABLE));
        // 가짜 서버가 요청을 한 번에 하나씩 처리하므로 두 번째 요청이 기록될 때까지 잠시 기다린다.
        Await.until(() -> server.requestsTo("/api/v1/predict").size() >= 2, Duration.ofSeconds(3));
        assertThat(server.requestsTo("/api/v1/predict")).hasSize(2);
    }

    @Test
    @DisplayName("401 같은 4xx는 다시 시도하지 않는다")
    void clientErrorDoesNotRetry() {
        server.handle("/api/v1/predict", request -> Reply.of(401, "{}"));

        assertThatThrownBy(() -> client.predict(candles(15))).isInstanceOf(BusinessException.class);
        assertThat(server.requestsTo("/api/v1/predict")).hasSize(1);
    }

    @Test
    @DisplayName("키가 없으면 X-API-KEY 헤더를 보내지 않는다")
    void noKeyNoHeader() {
        server.handle("/api/v1/predict", request -> Reply.ok(OK_BODY));
        ModelClient noKey = new ModelClient(new ModelProperties(true, server.url(), "", Duration.ofMillis(500), 30));

        noKey.predict(candles(15));

        assertThat(server.requestsTo("/api/v1/predict").get(0).headers()).doesNotContainKey("x-api-key");
    }

    @Test
    @DisplayName("설정 문자열에 키 값이 나오지 않는다")
    void toStringHidesKey() {
        assertThat(new ModelProperties(true, "http://x", "secret-value", Duration.ofMillis(500), 30).toString())
                .doesNotContain("secret-value");
    }
}
