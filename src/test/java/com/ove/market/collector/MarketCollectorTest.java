package com.ove.market.collector;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import com.ove.global.exception.BusinessException;
import com.ove.global.exception.ErrorCode;
import com.ove.market.client.UpbitCandleResponse;
import com.ove.market.client.UpbitClient;
import com.ove.support.Await;
import com.ove.support.CollectorContainers;
import com.ove.support.MutableClock;
import com.ove.support.RedisRecorder;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 수집기 전체 흐름을 실제 TimescaleDB·Redis(Testcontainers)와 가짜 체결 소스, 가짜 업비트 REST로 확인한다.
 * 시계는 테스트가 직접 움직인다. Docker 필요.
 */
class MarketCollectorTest {

    private static final Instant M0 = Instant.parse("2026-10-20T12:00:00Z");
    private static final Duration GRACE = Duration.ofMillis(1500);

    private static JdbcTemplate jdbcTemplate;
    private static RedisRecorder redis;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final UpbitClient upbitClient = mock(UpbitClient.class);
    private MutableClock clock;
    private CandleRepository repository;
    private MarketCollector collector;

    @BeforeAll
    static void setUpInfrastructure() {
        jdbcTemplate = new JdbcTemplate(new DriverManagerDataSource(CollectorContainers.TIMESCALE.getJdbcUrl(),
                CollectorContainers.TIMESCALE.getUsername(), CollectorContainers.TIMESCALE.getPassword()));
        redis = new RedisRecorder(CollectorContainers.redisHost(), CollectorContainers.redisPort());
    }

    @AfterAll
    static void tearDownInfrastructure() throws Exception {
        redis.close();
    }

    @BeforeEach
    void setUp() {
        redis.reset();
        clock = new MutableClock(M0.plusSeconds(10));
        repository = new CandleRepository(jdbcTemplate);
        repository.initSchema();
        jdbcTemplate.update("DELETE FROM candles");

        CollectorProperties properties = new CollectorProperties(true, List.of("KRW-BTC", "KRW-ETH"),
                "ws://unused", GRACE, Duration.ofMillis(250), Duration.ofSeconds(1), Duration.ofMinutes(10),
                Duration.ofSeconds(1), Duration.ofSeconds(30), Duration.ofMillis(250), Duration.ofSeconds(60),
                Duration.ofSeconds(90), Duration.ofSeconds(10), Duration.ofMillis(50), 3);
        MarketRedisPublisher publisher = new MarketRedisPublisher(redis.template(), objectMapper,
                properties.priceTtl(), properties.tickPublishInterval());
        collector = new MarketCollector(properties, new NoopTradeSource(), new CandleAggregator(GRACE), repository,
                publisher, upbitClient, clock);
    }

    @AfterEach
    void tearDown() {
        collector.stop();
        // TimescaleDB 컨테이너는 다른 테스트 클래스(특히 앱 전체를 띄우는 end-to-end 테스트)와 같이 쓰므로
        // 남은 봉이 다음 테스트의 보충 구간 계산에 끼어들지 않게 비운다.
        jdbcTemplate.update("DELETE FROM candles");
    }

    // ---------- 실시간 집계 → 저장 · 발행 ----------

    @Test
    @DisplayName("체결이 1분봉으로 마감되면 DB에 저장되고 candles.closed가 발행된다")
    void closedCandleIsStoredAndPublished() {
        collector.onConnected(M0.plusSeconds(10)); // 실시간 집계는 12:01부터
        Instant live = M0.plusSeconds(60);
        collector.onTrade(trade("KRW-BTC", "100", "1", live.plusSeconds(5)));
        collector.onTrade(trade("KRW-BTC", "130", "2", live.plusSeconds(20)));
        collector.onTrade(trade("KRW-BTC", "90", "1", live.plusSeconds(40)));
        collector.onTrade(trade("KRW-BTC", "110", "1", live.plusSeconds(55)));

        // 유예 전에는 마감하지 않는다
        collector.tick(live.plusSeconds(60));
        assertThat(rowCount()).isZero();

        collector.tick(live.plusSeconds(60).plus(GRACE));

        Map<String, Object> row = jdbcTemplate.queryForMap("SELECT * FROM candles WHERE symbol = 'KRW-BTC'");
        assertThat(((java.sql.Timestamp) row.get("time")).toInstant()).isEqualTo(live);
        assertThat((BigDecimal) row.get("open")).isEqualByComparingTo("100");
        assertThat((BigDecimal) row.get("high")).isEqualByComparingTo("130");
        assertThat((BigDecimal) row.get("low")).isEqualByComparingTo("90");
        assertThat((BigDecimal) row.get("close")).isEqualByComparingTo("110");
        assertThat((BigDecimal) row.get("volume")).isEqualByComparingTo("5");

        assertThat(Await.until(() -> redis.messagesOn("candles.closed:KRW-BTC").size() == 1)).isTrue();
        JsonNode closed = objectMapper.readTree(redis.messagesOn("candles.closed:KRW-BTC").get(0));
        assertThat(closed.get("time").asString()).isEqualTo(live.toString());
        assertThat(closed.get("close").decimalValue()).isEqualByComparingTo("110");
    }

    @Test
    @DisplayName("체결이 없던 분은 DB에도 봉이 생기지 않는다")
    void emptyMinuteIsNotStored() {
        collector.onConnected(M0.plusSeconds(10));
        Instant live = M0.plusSeconds(60);
        collector.onTrade(trade("KRW-BTC", "100", "1", live.plusSeconds(5)));
        collector.onTrade(trade("KRW-BTC", "200", "1", live.plusSeconds(125))); // 12:03

        collector.tick(live.plusSeconds(300));

        List<Instant> times = jdbcTemplate.query("SELECT time FROM candles ORDER BY time",
                (rs, rowNum) -> rs.getTimestamp(1).toInstant());
        assertThat(times).containsExactly(live, live.plusSeconds(120));
    }

    @Test
    @DisplayName("같은 봉을 다시 마감해도 (symbol, time)당 한 행만 남는다")
    void upsertKeepsSingleRow() {
        repository.upsert(new MinuteCandle("KRW-BTC", M0.plusSeconds(60), BigDecimal.ONE, BigDecimal.ONE,
                BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE));
        collector.onConnected(M0.plusSeconds(10));
        collector.onTrade(trade("KRW-BTC", "100", "1", M0.plusSeconds(65)));

        collector.tick(M0.plusSeconds(120).plus(GRACE));

        assertThat(rowCount()).isEqualTo(1);
        BigDecimal open = jdbcTemplate.queryForObject("SELECT open FROM candles", BigDecimal.class);
        assertThat(open).isEqualByComparingTo("100");
    }

    @Test
    @DisplayName("연결이 끊기면 진행 중이던 봉을 버려서 일부 체결만 담은 봉이 저장되지 않는다")
    void disconnectDiscardsOpenCandle() {
        collector.onConnected(M0.plusSeconds(10));
        collector.onTrade(trade("KRW-BTC", "100", "1", M0.plusSeconds(65)));

        collector.onDisconnected();
        collector.tick(M0.plusSeconds(300));

        assertThat(rowCount()).isZero();
        assertThat(redis.messagesOn("candles.closed:KRW-BTC")).isEmpty();
    }

    @Test
    @DisplayName("접속한 분(12:00)의 체결은 앞부분을 못 받았을 수 있어 봉에 쓰지 않지만 현재가는 갱신한다")
    void connectMinuteTradesAreNotAggregated() {
        collector.onConnected(M0.plusSeconds(10));

        collector.onTrade(trade("KRW-BTC", "100", "1", M0.plusSeconds(30)));
        collector.tick(M0.plusSeconds(300));

        assertThat(rowCount()).isZero();
        assertThat(redis.template().opsForHash().get("price:KRW-BTC", "price")).isEqualTo("100");
    }

    @Test
    @DisplayName("마감 뒤 늦게 온 체결은 이미 저장된 봉을 바꾸지 못한다")
    void lateTradeDoesNotChangeStoredCandle() {
        collector.onConnected(M0.plusSeconds(10));
        Instant live = M0.plusSeconds(60);
        collector.onTrade(trade("KRW-BTC", "100", "1", live.plusSeconds(5)));
        Instant closeTime = live.plusSeconds(60).plus(GRACE);
        collector.tick(closeTime);

        clock.set(closeTime.plusSeconds(1));
        collector.onTrade(trade("KRW-BTC", "999", "9", live.plusSeconds(30)));
        collector.tick(closeTime.plusSeconds(120));

        BigDecimal volume = jdbcTemplate.queryForObject("SELECT volume FROM candles", BigDecimal.class);
        assertThat(volume).isEqualByComparingTo("1");
        assertThat(redis.messagesOn("candles.closed:KRW-BTC")).hasSize(1);
    }

    // ---------- 재접속 때마다 최근 200분 REST 보충 ----------

    @Test
    @DisplayName("보충: DB가 비어 있으면 종목당 REST 1회로 최근 200분 창 안의 봉을 채우고, 창 밖의 봉은 쓰지 않는다")
    void backfillOnEmptyDatabaseFillsRecentWindow() {
        Instant liveFrom = M0.plusSeconds(60); // 창: 접속 직전 분 12:00 기준 최근 200분
        Instant windowStart = M0.minus(Duration.ofMinutes(199));
        when(upbitClient.getCandles(eq("minutes/1"), eq("KRW-BTC"), eq(liveFrom), eq(200))).thenReturn(List.of(
                upbitCandle(M0, "100"), upbitCandle(M0.minusSeconds(60), "99"), upbitCandle(windowStart, "98"),
                upbitCandle(windowStart.minusSeconds(60), "50"), // 창 밖(200분보다 오래됨)
                upbitCandle(liveFrom, "777"))); // 실시간 집계가 맡는 분이라 쓰지 않는다
        when(upbitClient.getCandles(eq("minutes/1"), eq("KRW-ETH"), eq(liveFrom), eq(200))).thenReturn(List.of());

        collector.backfill(liveFrom);

        List<Instant> times = jdbcTemplate.query("SELECT time FROM candles WHERE symbol = 'KRW-BTC' ORDER BY time",
                (rs, rowNum) -> rs.getTimestamp(1).toInstant());
        assertThat(times).containsExactly(windowStart, M0.minusSeconds(60), M0);
        verify(upbitClient, times(1)).getCandles("minutes/1", "KRW-BTC", liveFrom, 200);
        verify(upbitClient, times(1)).getCandles("minutes/1", "KRW-ETH", liveFrom, 200);
    }

    @Test
    @DisplayName("보충: 이미 저장된 봉은 REST 값으로 덮어쓰지 않고 없는 봉만 채운다")
    void backfillOnlyFillsMissingCandles() {
        Instant liveFrom = M0.plusSeconds(60);
        repository.upsert(new MinuteCandle("KRW-BTC", M0.minusSeconds(180), new BigDecimal("7"),
                new BigDecimal("7"), new BigDecimal("7"), new BigDecimal("7"), new BigDecimal("7")));
        when(upbitClient.getCandles(eq("minutes/1"), eq("KRW-BTC"), eq(liveFrom), eq(200)))
                .thenReturn(List.of(upbitCandle(M0, "103"), upbitCandle(M0.minusSeconds(60), "102"),
                        upbitCandle(M0.minusSeconds(120), "101"), upbitCandle(M0.minusSeconds(180), "999")));
        when(upbitClient.getCandles(eq("minutes/1"), eq("KRW-ETH"), eq(liveFrom), eq(200))).thenReturn(List.of());

        collector.backfill(liveFrom);

        List<Instant> times = jdbcTemplate.query("SELECT time FROM candles WHERE symbol = 'KRW-BTC' ORDER BY time",
                (rs, rowNum) -> rs.getTimestamp(1).toInstant());
        assertThat(times).containsExactly(M0.minusSeconds(180), M0.minusSeconds(120), M0.minusSeconds(60), M0);
        // 원래 있던 11:57봉은 REST 값(999)으로 바뀌지 않는다
        BigDecimal original = jdbcTemplate.queryForObject(
                "SELECT open FROM candles WHERE symbol = 'KRW-BTC' AND time = ?", BigDecimal.class,
                java.sql.Timestamp.from(M0.minusSeconds(180)));
        assertThat(original).isEqualByComparingTo("7");
    }

    @Test
    @DisplayName("보충: 보충 도중 실시간 집계가 같은 봉을 먼저 저장했다면 REST 값으로 덮어쓰지 않는다")
    void backfillDoesNotOverwriteAlreadyStoredCandle() {
        Instant liveFrom = M0.plusSeconds(60);
        when(upbitClient.getCandles(eq("minutes/1"), eq("KRW-BTC"), eq(liveFrom), eq(200))).thenAnswer(invocation -> {
            // REST 응답을 기다리는 사이에 같은 (symbol, time) 봉이 먼저 마감 저장된 상황
            repository.upsert(new MinuteCandle("KRW-BTC", M0, new BigDecimal("1"), new BigDecimal("1"),
                    new BigDecimal("1"), new BigDecimal("1"), new BigDecimal("1")));
            return List.of(upbitCandle(M0, "555"));
        });
        when(upbitClient.getCandles(eq("minutes/1"), eq("KRW-ETH"), eq(liveFrom), eq(200))).thenReturn(List.of());

        collector.backfill(liveFrom);

        BigDecimal close = jdbcTemplate.queryForObject("SELECT close FROM candles WHERE symbol = 'KRW-BTC'",
                BigDecimal.class);
        assertThat(close).isEqualByComparingTo("1");
    }

    @Test
    @DisplayName("보충: 빠진 게 없어도 종목당 REST 1회를 부르고 이미 있는 봉은 그대로 둔다 (같은 봉을 다시 받아도 안전)")
    void backfillIsIdempotent() {
        Instant liveFrom = M0.plusSeconds(60);
        when(upbitClient.getCandles(eq("minutes/1"), eq("KRW-BTC"), eq(liveFrom), eq(200)))
                .thenReturn(List.of(upbitCandle(M0, "100")));
        when(upbitClient.getCandles(eq("minutes/1"), eq("KRW-ETH"), eq(liveFrom), eq(200))).thenReturn(List.of());

        collector.backfill(liveFrom);
        collector.backfill(liveFrom);

        assertThat(rowCount()).isEqualTo(1);
        verify(upbitClient, times(2)).getCandles("minutes/1", "KRW-BTC", liveFrom, 200);
    }

    @Test
    @DisplayName("보충이 한 번 실패해도, 그 뒤 실시간 봉이 저장돼 있어도, 다음 재접속에서 200분 안의 구멍이 메워진다")
    void holeIsFilledOnNextReconnectEvenAfterLaterCandlesWereStored() {
        // 1) 첫 재접속(실시간 12:01부터): 보충이 실패해서 11:59, 12:00 두 분이 비어 있다
        Instant firstLiveFrom = M0.plusSeconds(60);
        when(upbitClient.getCandles(eq("minutes/1"), eq("KRW-BTC"), eq(firstLiveFrom), eq(200)))
                .thenThrow(new BusinessException(ErrorCode.UPSTREAM_UNAVAILABLE));
        assertThatThrownBy(() -> collector.backfill(firstLiveFrom))
                .isInstanceOf(BusinessException.class);
        assertThat(rowCount()).isZero();

        // 2) 그 뒤 연결이 살아서 실시간 봉이 저장됨 → 예전 방식("마지막 봉 다음부터")이면 앞의 구멍을 더는 찾지 못한다
        repository.upsert(new MinuteCandle("KRW-BTC", M0.plus(Duration.ofMinutes(5)), new BigDecimal("500"),
                new BigDecimal("500"), new BigDecimal("500"), new BigDecimal("500"), new BigDecimal("1")));

        // 3) 두 번째 재접속(실시간 12:10부터): 최근 200분을 다시 받아 구멍만 채운다
        Instant secondLiveFrom = M0.plus(Duration.ofMinutes(10));
        when(upbitClient.getCandles(eq("minutes/1"), eq("KRW-BTC"), eq(secondLiveFrom), eq(200))).thenReturn(List.of(
                upbitCandle(M0.plus(Duration.ofMinutes(5)), "999"), // 이미 저장된 실시간 봉: REST 값으로 안 바뀐다
                upbitCandle(M0, "100"), upbitCandle(M0.minusSeconds(60), "99")));
        when(upbitClient.getCandles(eq("minutes/1"), eq("KRW-ETH"), eq(secondLiveFrom), eq(200))).thenReturn(List.of());

        collector.backfill(secondLiveFrom);

        List<Instant> times = jdbcTemplate.query("SELECT time FROM candles WHERE symbol = 'KRW-BTC' ORDER BY time",
                (rs, rowNum) -> rs.getTimestamp(1).toInstant());
        assertThat(times).containsExactly(M0.minusSeconds(60), M0, M0.plus(Duration.ofMinutes(5)));
        BigDecimal liveOpen = jdbcTemplate.queryForObject(
                "SELECT open FROM candles WHERE symbol = 'KRW-BTC' AND time = ?", BigDecimal.class,
                java.sql.Timestamp.from(M0.plus(Duration.ofMinutes(5))));
        assertThat(liveOpen).isEqualByComparingTo("500");
    }

    @Test
    @DisplayName("보충한 봉은 candles.closed 알림을 내지 않는다 (오래된 봉으로 AI를 부르지 않게)")
    void backfillDoesNotPublishClosedEvents() {
        Instant liveFrom = M0.plusSeconds(60);
        when(upbitClient.getCandles(eq("minutes/1"), eq("KRW-BTC"), eq(liveFrom), eq(200)))
                .thenReturn(List.of(upbitCandle(M0, "100")));
        when(upbitClient.getCandles(eq("minutes/1"), eq("KRW-ETH"), eq(liveFrom), eq(200))).thenReturn(List.of());

        collector.backfill(liveFrom);

        assertThat(rowCount()).isEqualTo(1);
        assertThat(redis.messages()).isEmpty();
    }

    @Test
    @DisplayName("연결되면 접속한 분이 끝난 뒤 유예를 두고 자동으로 보충한다 (실제 스케줄러)")
    void onConnectedSchedulesBackfill() {
        Instant liveFrom = M0.plusSeconds(60);
        // 접속 시각은 12:00:10이지만 시계는 이미 보충 시점(12:01:01.5) 이후라서 바로 실행된다
        clock.set(liveFrom.plus(GRACE));
        when(upbitClient.getCandles(eq("minutes/1"), eq("KRW-BTC"), eq(liveFrom), eq(200)))
                .thenReturn(List.of(upbitCandle(M0, "100")));
        when(upbitClient.getCandles(eq("minutes/1"), eq("KRW-ETH"), eq(liveFrom), eq(200))).thenReturn(List.of());

        collector.onConnected(M0.plusSeconds(10));

        assertThat(Await.until(() -> rowCount() == 1)).isTrue();
    }

    @Test
    @DisplayName("같은 분 안에 연결이 여러 번 붙어도 보충은 한 번만 한다 (REST 호출 증폭 방지)")
    void backfillRunsOncePerMinuteEvenIfReconnectedRepeatedly() throws Exception {
        Instant liveFrom = M0.plusSeconds(60);
        clock.set(liveFrom.plus(GRACE));
        when(upbitClient.getCandles(eq("minutes/1"), anyString(), eq(liveFrom), eq(200))).thenReturn(List.of());

        collector.onConnected(M0.plusSeconds(10));
        collector.onConnected(M0.plusSeconds(15));
        collector.onConnected(M0.plusSeconds(20));

        assertThat(Await.until(() -> mockingCallCount() >= 2)).isTrue();
        Thread.sleep(300); // 중복 보충이 더 있는지 잠깐 기다려 확인
        verify(upbitClient, times(1)).getCandles("minutes/1", "KRW-BTC", liveFrom, 200);
        verify(upbitClient, times(1)).getCandles("minutes/1", "KRW-ETH", liveFrom, 200);
    }

    @Test
    @DisplayName("다음 분에 다시 연결되면 새로 보충한다")
    void backfillRunsAgainInNextMinute() {
        Instant firstLiveFrom = M0.plusSeconds(60);
        Instant secondLiveFrom = M0.plusSeconds(120);
        when(upbitClient.getCandles(anyString(), anyString(), any(), anyInt())).thenReturn(List.of());

        clock.set(firstLiveFrom.plus(GRACE));
        collector.onConnected(M0.plusSeconds(10));
        assertThat(Await.until(() -> mockingCallCount() >= 2)).isTrue();

        clock.set(secondLiveFrom.plus(GRACE));
        collector.onConnected(M0.plusSeconds(70));

        assertThat(Await.until(() -> mockingCallCount() >= 4)).isTrue();
        verify(upbitClient, times(1)).getCandles("minutes/1", "KRW-BTC", secondLiveFrom, 200);
    }

    @Test
    @DisplayName("보충을 포기한 분에 다시 연결되면 한 번 더 시도할 수 있다")
    void backfillCanBeRetriedAfterGivingUp() throws Exception {
        Instant liveFrom = M0.plusSeconds(60);
        clock.set(liveFrom.plus(GRACE));
        when(upbitClient.getCandles(anyString(), anyString(), any(), anyInt()))
                .thenThrow(new BusinessException(ErrorCode.UPSTREAM_UNAVAILABLE));
        collector.onConnected(M0.plusSeconds(10));
        assertThat(Await.until(() -> mockingCallCount() >= 3)).isTrue();
        Thread.sleep(300); // 3번째 시도가 끝나고 포기 처리(점유 해제)될 때까지 대기

        collector.onConnected(M0.plusSeconds(30));

        assertThat(Await.until(() -> mockingCallCount() >= 6)).isTrue();
    }

    @Test
    @DisplayName("보충이 실패하면 설정한 횟수까지 다시 시도한다")
    void backfillRetriesOnFailure() {
        Instant liveFrom = M0.plusSeconds(60);
        clock.set(liveFrom.plus(GRACE));
        when(upbitClient.getCandles(eq("minutes/1"), eq("KRW-BTC"), eq(liveFrom), eq(200)))
                .thenThrow(new BusinessException(ErrorCode.UPSTREAM_UNAVAILABLE))
                .thenReturn(List.of(upbitCandle(M0, "100")));
        when(upbitClient.getCandles(eq("minutes/1"), eq("KRW-ETH"), eq(liveFrom), eq(200))).thenReturn(List.of());

        collector.onConnected(M0.plusSeconds(10));

        assertThat(Await.until(() -> rowCount() == 1)).isTrue();
        verify(upbitClient, times(2)).getCandles("minutes/1", "KRW-BTC", liveFrom, 200);
    }

    @Test
    @DisplayName("보충이 계속 실패하면 3번 시도하고 포기한다")
    void backfillGivesUpAfterMaxAttempts() throws Exception {
        Instant liveFrom = M0.plusSeconds(60);
        clock.set(liveFrom.plus(GRACE));
        when(upbitClient.getCandles(anyString(), anyString(), any(), anyInt()))
                .thenThrow(new BusinessException(ErrorCode.UPSTREAM_UNAVAILABLE));

        collector.onConnected(M0.plusSeconds(10));

        assertThat(Await.until(() -> mockingCallCount() >= 3)).isTrue();
        Thread.sleep(300); // 더 시도하지 않는지 잠깐 기다려 확인
        assertThat(mockingCallCount()).isEqualTo(3);
        assertThat(rowCount()).isZero();
    }

    // ---------- 현재가 (Redis) ----------

    @Test
    @DisplayName("체결마다 price:{symbol}가 갱신되고 tick이 발행된다")
    void tradeUpdatesPriceAndPublishesTick() {
        collector.onTrade(trade("KRW-ETH", "5000000", "1", M0.plusSeconds(5)));

        assertThat(redis.template().opsForHash().get("price:KRW-ETH", "price")).isEqualTo("5000000");
        assertThat(Await.until(() -> redis.messagesOn("prices:KRW-ETH").size() == 1)).isTrue();
    }

    private int mockingCallCount() {
        return org.mockito.Mockito.mockingDetails(upbitClient).getInvocations().size();
    }

    private int rowCount() {
        Integer count = jdbcTemplate.queryForObject("SELECT count(*) FROM candles", Integer.class);
        return count == null ? 0 : count;
    }

    private Trade trade(String symbol, String price, String volume, Instant tradeTimestamp) {
        return new Trade(symbol, new BigDecimal(price), new BigDecimal(volume), tradeTimestamp);
    }

    private UpbitCandleResponse upbitCandle(Instant time, String price) {
        String utc = time.toString();
        return UpbitCandleResponse.builder()
                .candleDateTimeUtc(utc.substring(0, utc.length() - 1))
                .openingPrice(new BigDecimal(price))
                .highPrice(new BigDecimal(price))
                .lowPrice(new BigDecimal(price))
                .tradePrice(new BigDecimal(price))
                .candleAccTradeVolume(BigDecimal.ONE)
                .build();
    }

    private static final class NoopTradeSource implements TradeSource {

        @Override
        public void start(TradeListener listener) {
        }

        @Override
        public void stop() {
        }
    }
}
