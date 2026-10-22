package com.ove.market.collector.kiwoom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.ove.market.collector.MinuteCandle;
import com.ove.support.FakeKiwoomRestServer;
import com.ove.support.FakeKiwoomRestServer.Reply;
import com.ove.support.MutableClock;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 가짜 키움 서버로 분봉 보충(ka10080)의 요청 형식, 시각·부호 변환, 연속조회, 오류 처리를 확인한다.
 */
class KiwoomChartBackfillSourceTest {

    // 실시간 집계 시작 10:01 KST → 보충 대상은 그 직전 분(10:00 KST = 01:00 UTC)까지
    private static final Instant LIVE_FROM = Instant.parse("2026-10-21T01:01:00Z");

    private final ObjectMapper objectMapper = new ObjectMapper();
    private FakeKiwoomRestServer server;
    private final AtomicInteger issued = new AtomicInteger();

    @BeforeEach
    void setUp() throws Exception {
        server = new FakeKiwoomRestServer();
        server.handle("/oauth2/token", request -> Reply.ok(FakeKiwoomRestServer.tokenJson(
                "TEST-TOKEN-" + issued.incrementAndGet(), "20261021160000")));
    }

    @AfterEach
    void tearDown() {
        server.close();
    }

    @Test
    @DisplayName("요청 형식: POST /api/dostk/chart, api-id ka10080, Bearer 토큰, stk_cd·tic_scope 1·upd_stkpc_tp·base_dt")
    void requestFormat() throws Exception {
        server.handle("/api/dostk/chart", request -> Reply.ok(chart()));

        source("START").fetchRecent("005930", LIVE_FROM, 200);

        FakeKiwoomRestServer.Received request = server.requestsTo("/api/dostk/chart").get(0);
        assertThat(request.headers()).containsEntry("api-id", "ka10080")
                .containsEntry("authorization", "Bearer TEST-TOKEN-1")
                .doesNotContainKey("cont-yn").doesNotContainKey("next-key");
        JsonNode body = objectMapper.readTree(request.body());
        assertThat(body.get("stk_cd").asString()).isEqualTo("005930");
        assertThat(body.get("tic_scope").asString()).isEqualTo("1");
        assertThat(body.get("upd_stkpc_tp").asString()).isEqualTo("1");
        assertThat(body.get("base_dt").asString()).isEqualTo("20261021"); // 대상 분의 KST 날짜
    }

    @Test
    @DisplayName("START 라벨: cntr_tm(KST)을 봉 시작 시각으로 읽고, 부호 붙은 가격은 절대값으로 바꾼다")
    void startLabel() {
        server.handle("/api/dostk/chart", request -> Reply.ok(chart(
                row("20261021100000", "-78800", "-78850", "-78900", "-78700", "7913"),
                row("20261021095900", "+78900", "+78800", "+79000", "+78750", "16084"))));

        List<MinuteCandle> candles = source("START").fetchRecent("005930", LIVE_FROM, 200);

        assertThat(candles).hasSize(2);
        MinuteCandle latest = candles.get(0);
        assertThat(latest.symbol()).isEqualTo("005930");
        assertThat(latest.time()).isEqualTo(Instant.parse("2026-10-21T01:00:00Z")); // 10:00 KST
        assertThat(latest.close()).isEqualByComparingTo("78800");
        assertThat(latest.open()).isEqualByComparingTo("78850");
        assertThat(latest.high()).isEqualByComparingTo("78900");
        assertThat(latest.low()).isEqualByComparingTo("78700");
        assertThat(latest.volume()).isEqualByComparingTo("7913");
        assertThat(candles.get(1).time()).isEqualTo(Instant.parse("2026-10-21T00:59:00Z"));
        assertThat(candles.get(1).close()).isEqualByComparingTo("78900");
    }

    @Test
    @DisplayName("END 라벨: cntr_tm이 봉의 끝 시각이므로 한 분 앞으로 당겨 봉 시작 시각으로 맞춘다")
    void endLabel() {
        server.handle("/api/dostk/chart", request -> Reply.ok(chart(
                row("20261021100100", "78800", "78850", "78900", "78700", "7913"))));

        List<MinuteCandle> candles = source("END").fetchRecent("005930", LIVE_FROM, 200);

        assertThat(candles).extracting(MinuteCandle::time).containsExactly(Instant.parse("2026-10-21T01:00:00Z"));
    }

    @Test
    @DisplayName("정규장(09:00 ~ 15:30 봉) 밖의 봉은 쓰지 않는다")
    void filtersNonRegularBars() {
        server.handle("/api/dostk/chart", request -> Reply.ok(chart(
                row("20261021153100", "1", "1", "1", "1", "1"),   // 15:31 봉: 장 마감 뒤
                row("20261021153000", "2", "2", "2", "2", "2"),   // 15:30 봉: 종가 단일가 봉
                row("20261021090000", "3", "3", "3", "3", "3"),   // 09:00 봉: 첫 봉
                row("20261021085900", "4", "4", "4", "4", "4")))); // 08:59 봉: 장 시작 전

        List<MinuteCandle> candles = source("START").fetchRecent("005930", LIVE_FROM, 200);

        assertThat(candles).extracting(candle -> candle.close().intValue()).containsExactly(2, 3);
    }

    @Test
    @DisplayName("연속조회: cont-yn=Y면 next-key를 담아 이어서 요청하고, 창의 시작보다 오래된 봉이 나오면 멈춘다")
    void continuationStopsAtWindowStart() {
        AtomicInteger calls = new AtomicInteger();
        server.handle("/api/dostk/chart", request -> {
            if (calls.getAndIncrement() == 0) {
                return new Reply(200, chart(row("20261021100000", "1", "1", "1", "1", "1")),
                        Map.of("cont-yn", "Y", "next-key", "KEY-1"));
            }
            // 창(09:59 ~ 10:00)보다 오래된 09:58 봉이 나왔으니 서버가 더 이어서 주겠다고 해도 여기서 멈춘다
            return new Reply(200, chart(row("20261021095800", "2", "2", "2", "2", "2")),
                    Map.of("cont-yn", "Y", "next-key", "KEY-2"));
        });

        // 창: 10:00 KST 기준 최근 2분 (09:59 ~ 10:00)
        List<MinuteCandle> candles = source("START").fetchRecent("005930", LIVE_FROM, 2);

        // 창 밖의 봉(09:58)도 받아 오지만 걸러 내는 것은 MarketCollector의 몫이다
        assertThat(candles).hasSize(2);
        List<FakeKiwoomRestServer.Received> chartCalls = server.requestsTo("/api/dostk/chart");
        assertThat(chartCalls).hasSize(2);
        assertThat(chartCalls.get(1).headers()).containsEntry("cont-yn", "Y").containsEntry("next-key", "KEY-1");
    }

    @Test
    @DisplayName("창 안의 봉이 모자라도 cont-yn이 N이면 거기서 멈춘다")
    void continuationStopsWhenNoMorePages() {
        server.handle("/api/dostk/chart", request -> Reply.ok(chart(
                row("20261021100000", "1", "1", "1", "1", "1"))));

        source("START").fetchRecent("005930", LIVE_FROM, 200);

        assertThat(server.requestsTo("/api/dostk/chart")).hasSize(1);
    }

    @Test
    @DisplayName("서버가 계속 이어서 조회하라고 해도 최대 10쪽까지만 요청한다")
    void capsPages() {
        AtomicInteger page = new AtomicInteger();
        server.handle("/api/dostk/chart", request -> {
            int minute = 59 - page.getAndIncrement(); // 쪽마다 한 분씩 과거로 (창 안에 머문다)
            return new Reply(200, chart(row("202610210" + "9" + String.format("%02d", Math.max(minute, 0)) + "00",
                    "1", "1", "1", "1", "1")), Map.of("cont-yn", "Y", "next-key", "K"));
        });

        source("START").fetchRecent("005930", LIVE_FROM, 200);

        assertThat(server.requestsTo("/api/dostk/chart")).hasSize(10);
    }

    @Test
    @DisplayName("해석할 수 없는 행은 건너뛰고 나머지는 쓴다")
    void skipsMalformedRows() {
        server.handle("/api/dostk/chart", request -> Reply.ok(chart(
                row("not-a-time", "1", "1", "1", "1", "1"),
                row("20261021100000", "abc", "1", "1", "1", "1"),
                row("20261021095900", "5", "5", "5", "5", "5"))));

        List<MinuteCandle> candles = source("START").fetchRecent("005930", LIVE_FROM, 200);

        assertThat(candles).hasSize(1);
        assertThat(candles.get(0).close()).isEqualByComparingTo("5");
    }

    @Test
    @DisplayName("토큰 오류(8005)면 토큰을 버리고 예외를 올려서, 다음 시도에서 새 토큰으로 호출한다")
    void tokenErrorInvalidatesToken() {
        AtomicInteger calls = new AtomicInteger();
        server.handle("/api/dostk/chart", request -> calls.getAndIncrement() == 0
                ? Reply.ok("{\"return_code\":8005,\"return_msg\":\"Token이 유효하지 않습니다\"}")
                : Reply.ok(chart(row("20261021100000", "1", "1", "1", "1", "1"))));
        KiwoomChartBackfillSource source = source("START");

        assertThatThrownBy(() -> source.fetchRecent("005930", LIVE_FROM, 200))
                .isInstanceOfSatisfying(KiwoomException.class, e -> assertThat(e.getCode()).isEqualTo(8005));
        List<MinuteCandle> retried = source.fetchRecent("005930", LIVE_FROM, 200);

        assertThat(retried).hasSize(1);
        assertThat(server.requestsTo("/oauth2/token")).hasSize(2);
        assertThat(server.requestsTo("/api/dostk/chart").get(1).headers()).containsEntry("authorization",
                "Bearer TEST-TOKEN-2");
    }

    @Test
    @DisplayName("호출 한도 오류(1700)는 토큰을 버리지 않고 예외만 올린다 (수집기가 5초 뒤 다시 시도)")
    void rateLimitErrorKeepsToken() {
        server.handle("/api/dostk/chart", request -> Reply.ok("{\"return_code\":1700,\"return_msg\":\"초과\"}"));
        KiwoomChartBackfillSource source = source("START");

        assertThatThrownBy(() -> source.fetchRecent("005930", LIVE_FROM, 200))
                .isInstanceOfSatisfying(KiwoomException.class, e -> {
                    assertThat(e.getCode()).isEqualTo(1700);
                    assertThat(e.isTokenProblem()).isFalse();
                });
        assertThatThrownBy(() -> source.fetchRecent("005930", LIVE_FROM, 200)).isInstanceOf(KiwoomException.class);

        assertThat(server.requestsTo("/oauth2/token")).hasSize(1);
    }

    // 키움 응답 모양의 JSON. 행은 최신순으로 넘긴다
    private String chart(String... rows) {
        return "{\"stk_cd\":\"005930\",\"stk_min_pole_chart_qry\":[" + String.join(",", rows)
                + "],\"return_code\":0,\"return_msg\":\"정상적으로 처리되었습니다\"}";
    }

    private String row(String cntrTm, String close, String open, String high, String low, String volume) {
        return "{\"cur_prc\":\"" + close + "\",\"trde_qty\":\"" + volume + "\",\"cntr_tm\":\"" + cntrTm
                + "\",\"open_pric\":\"" + open + "\",\"high_pric\":\"" + high + "\",\"low_pric\":\"" + low
                + "\",\"pred_pre\":\"-600\",\"pred_pre_sig\":\"5\"}";
    }

    private KiwoomChartBackfillSource source(String label) {
        KiwoomProperties properties = new KiwoomProperties(true, List.of("005930"), "TEST-APP-KEY-not-real",
                "TEST-APP-SECRET-not-real", server.url(), "wss://unused", Duration.ofMinutes(10),
                Duration.ofSeconds(120), Duration.ofSeconds(15), Duration.ZERO, Duration.ofSeconds(1),
                Duration.ofSeconds(2), true, label, "1");
        KiwoomRestClient restClient = new KiwoomRestClient(server.url(), Duration.ofSeconds(1), Duration.ofSeconds(2),
                Duration.ZERO, objectMapper);
        MutableClock clock = new MutableClock(Instant.parse("2026-10-21T01:00:00Z"));
        return new KiwoomChartBackfillSource(restClient, new KiwoomTokenProvider(restClient, properties, clock),
                properties);
    }
}
