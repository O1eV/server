package com.ove.market.collector.kiwoom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.ove.support.FakeKiwoomRestServer;

import tools.jackson.databind.ObjectMapper;

/**
 * 시세 조회 TR만 허용하고 주문·계좌 TR은 호출 경로 자체를 막는지 확인한다.
 */
class KiwoomTrAllowlistTest {

    private FakeKiwoomRestServer server;

    @BeforeEach
    void setUp() throws Exception {
        server = new FakeKiwoomRestServer();
    }

    @AfterEach
    void tearDown() {
        server.close();
    }

    @Test
    @DisplayName("허용되는 REST TR은 토큰 발급·폐기(au10001, au10002)와 분봉 차트(ka10080)뿐이고 경로도 맞아야 한다")
    void allowsOnlyQuoteAndAuthRestCalls() {
        assertThatCode(() -> {
            KiwoomTrAllowlist.requireRest("au10001", "/oauth2/token");
            KiwoomTrAllowlist.requireRest("au10002", "/oauth2/revoke");
            KiwoomTrAllowlist.requireRest("ka10080", "/api/dostk/chart");
        }).doesNotThrowAnyException();

        // 허용된 TR이어도 경로가 다르면 거부
        assertThatThrownBy(() -> KiwoomTrAllowlist.requireRest("ka10080", "/api/dostk/ordr"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> KiwoomTrAllowlist.requireRest("au10001", "/api/dostk/chart"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest(name = "주문·계좌 성격의 REST TR {0}은 거부")
    @ValueSource(strings = {"kt10000", "kt10001", "kt10002", "kt10003", "kt00001", "kt00004", "ka10001", "kt50020", ""})
    void rejectsOrderAndAccountRestCalls(String apiId) {
        assertThatThrownBy(() -> KiwoomTrAllowlist.requireRest(apiId, "/api/dostk/ordr"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("허용되지 않은");
    }

    @Test
    @DisplayName("실시간은 0B(주식체결)와 0s(장시작시간)만 허용하고 주문체결(00)·잔고(04) 등은 거부")
    void allowsOnlyTradeAndMarketHoursRealtime() {
        assertThatCode(() -> {
            KiwoomTrAllowlist.requireRealtime("0B");
            KiwoomTrAllowlist.requireRealtime("0s");
        }).doesNotThrowAnyException();

        for (String type : new String[] {"00", "04", "0A", "0D", "1h", "0b", ""}) {
            assertThatThrownBy(() -> KiwoomTrAllowlist.requireRealtime(type))
                    .as("type=%s", type)
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    @DisplayName("REST 클라이언트는 허용되지 않은 TR이면 네트워크 요청을 보내기 전에 거부한다")
    void restClientRejectsBeforeSendingAnything() {
        KiwoomRestClient client = new KiwoomRestClient(server.url(), Duration.ofSeconds(1), Duration.ofSeconds(1),
                Duration.ZERO, new ObjectMapper());

        assertThatThrownBy(() -> client.post("kt10000", "/api/dostk/ordr", "token",
                Map.of("dmst_stex_tp", "KRX", "stk_cd", "005930", "ord_qty", "1"), null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> client.post("kt00001", "/api/dostk/acnt", "token", Map.of(), null))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(server.requests()).isEmpty();
    }
}
