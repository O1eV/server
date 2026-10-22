package com.ove.market.collector;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class CandleAggregatorTest {

    private static final Duration GRACE = Duration.ofMillis(1500);
    private static final Instant M0 = Instant.parse("2026-10-20T12:00:00Z");

    private final CandleAggregator aggregator = new CandleAggregator(GRACE);

    @Test
    @DisplayName("시가=첫 체결, 종가=마지막 체결, 고가·저가, 거래량=수량 합")
    void ohlcv() {
        aggregator.add(trade("KRW-BTC", "100", "1.5", M0.plusMillis(1000)), M0.plusMillis(1000));
        aggregator.add(trade("KRW-BTC", "120", "0.5", M0.plusMillis(2000)), M0.plusMillis(2000));
        aggregator.add(trade("KRW-BTC", "90", "2", M0.plusMillis(3000)), M0.plusMillis(3000));
        aggregator.add(trade("KRW-BTC", "110", "1", M0.plusMillis(59_000)), M0.plusMillis(59_000));

        List<MinuteCandle> closed = aggregator.closeDue(M0.plusSeconds(60).plus(GRACE));

        assertThat(closed).hasSize(1);
        MinuteCandle candle = closed.get(0);
        assertThat(candle.symbol()).isEqualTo("KRW-BTC");
        assertThat(candle.time()).isEqualTo(M0);
        assertThat(candle.open()).isEqualByComparingTo("100");
        assertThat(candle.high()).isEqualByComparingTo("120");
        assertThat(candle.low()).isEqualByComparingTo("90");
        assertThat(candle.close()).isEqualByComparingTo("110");
        assertThat(candle.volume()).isEqualByComparingTo("5.0");
    }

    @Test
    @DisplayName("분 경계: 12:00:59.999는 12:00봉, 12:01:00.000은 12:01봉")
    void minuteBoundary() {
        Instant lastOfMinute = M0.plusMillis(59_999);
        Instant firstOfNext = M0.plusSeconds(60);
        aggregator.add(trade("KRW-BTC", "100", "1", lastOfMinute), lastOfMinute);
        aggregator.add(trade("KRW-BTC", "200", "1", firstOfNext), firstOfNext);

        List<MinuteCandle> closed = aggregator.closeDue(M0.plusSeconds(120).plus(GRACE));

        assertThat(closed).extracting(MinuteCandle::time).containsExactly(M0, M0.plusSeconds(60));
        assertThat(closed.get(0).close()).isEqualByComparingTo("100");
        assertThat(closed.get(1).open()).isEqualByComparingTo("200");
    }

    @Test
    @DisplayName("마감 유예: 분이 끝나도 유예 시간 전에는 마감하지 않고, 유예가 지나면 마감한다")
    void graceDelay() {
        aggregator.add(trade("KRW-BTC", "100", "1", M0.plusSeconds(10)), M0.plusSeconds(10));

        Instant minuteEnd = M0.plusSeconds(60);
        assertThat(aggregator.closeDue(minuteEnd)).isEmpty();
        assertThat(aggregator.closeDue(minuteEnd.plus(GRACE).minusMillis(1))).isEmpty();
        assertThat(aggregator.closeDue(minuteEnd.plus(GRACE))).hasSize(1);
        // 한 번 마감한 봉은 다시 나오지 않는다
        assertThat(aggregator.closeDue(minuteEnd.plus(GRACE).plusSeconds(10))).isEmpty();
    }

    @Test
    @DisplayName("유예 안에 도착한 늦은 체결은 봉에 반영된다")
    void tradeWithinGraceIsIncluded() {
        aggregator.add(trade("KRW-BTC", "100", "1", M0.plusSeconds(30)), M0.plusSeconds(30));
        Instant lateTradeTimestamp = M0.plusMillis(59_900);
        Instant arrival = M0.plusSeconds(60).plusMillis(1000); // 분이 끝난 뒤 1초, 유예(1.5초) 안

        boolean accepted = aggregator.add(trade("KRW-BTC", "130", "2", lateTradeTimestamp), arrival);

        assertThat(accepted).isTrue();
        MinuteCandle candle = aggregator.closeDue(M0.plusSeconds(60).plus(GRACE)).get(0);
        assertThat(candle.close()).isEqualByComparingTo("130");
        assertThat(candle.high()).isEqualByComparingTo("130");
        assertThat(candle.volume()).isEqualByComparingTo("3");
    }

    @Test
    @DisplayName("마감 뒤에 늦게 온 체결은 무시하고 마감된 봉은 바뀌지 않는다")
    void tradeAfterCloseIsIgnored() {
        aggregator.add(trade("KRW-BTC", "100", "1", M0.plusSeconds(30)), M0.plusSeconds(30));
        Instant closeAt = M0.plusSeconds(60).plus(GRACE);
        List<MinuteCandle> closed = aggregator.closeDue(closeAt);
        assertThat(closed).hasSize(1);

        boolean accepted = aggregator.add(trade("KRW-BTC", "999", "9", M0.plusSeconds(59)), closeAt.plusMillis(1));

        assertThat(accepted).isFalse();
        assertThat(aggregator.closeDue(closeAt.plusSeconds(120))).isEmpty();
    }

    @Test
    @DisplayName("아직 마감 처리는 안 됐어도 유예가 지난 뒤 도착한 체결은 무시한다")
    void tradeAfterGraceIsIgnoredEvenBeforeCloseTick() {
        aggregator.add(trade("KRW-BTC", "100", "1", M0.plusSeconds(30)), M0.plusSeconds(30));

        boolean accepted = aggregator.add(trade("KRW-BTC", "999", "9", M0.plusSeconds(59)),
                M0.plusSeconds(60).plus(GRACE));

        assertThat(accepted).isFalse();
        assertThat(aggregator.closeDue(M0.plusSeconds(60).plus(GRACE)).get(0).volume()).isEqualByComparingTo("1");
    }

    @Test
    @DisplayName("체결이 없던 분은 봉을 만들지 않는다")
    void emptyMinuteHasNoCandle() {
        aggregator.add(trade("KRW-BTC", "100", "1", M0.plusSeconds(10)), M0.plusSeconds(10));
        aggregator.add(trade("KRW-BTC", "300", "1", M0.plusSeconds(130)), M0.plusSeconds(130));

        List<MinuteCandle> closed = aggregator.closeDue(M0.plusSeconds(300));

        // 12:01은 체결이 없어서 빠진다
        assertThat(closed).extracting(MinuteCandle::time).containsExactly(M0, M0.plusSeconds(120));
    }

    @Test
    @DisplayName("같은 분 안에서 체결이 시각 순서가 아닌 순서로 와도 시가·종가는 체결 시각 기준")
    void outOfOrderWithinMinute() {
        aggregator.add(trade("KRW-BTC", "200", "1", M0.plusSeconds(30)), M0.plusSeconds(30));
        aggregator.add(trade("KRW-BTC", "100", "1", M0.plusSeconds(5)), M0.plusSeconds(31));
        aggregator.add(trade("KRW-BTC", "300", "1", M0.plusSeconds(50)), M0.plusSeconds(51));
        aggregator.add(trade("KRW-BTC", "250", "1", M0.plusSeconds(40)), M0.plusSeconds(52));

        MinuteCandle candle = aggregator.closeDue(M0.plusSeconds(60).plus(GRACE)).get(0);

        assertThat(candle.open()).isEqualByComparingTo("100");
        assertThat(candle.close()).isEqualByComparingTo("300");
        assertThat(candle.high()).isEqualByComparingTo("300");
        assertThat(candle.low()).isEqualByComparingTo("100");
    }

    @Test
    @DisplayName("종목별로 따로 집계하고, 같은 분의 봉은 종목 이름순으로 나온다")
    void perSymbol() {
        aggregator.add(trade("KRW-ETH", "50", "1", M0.plusSeconds(10)), M0.plusSeconds(10));
        aggregator.add(trade("KRW-BTC", "100", "1", M0.plusSeconds(20)), M0.plusSeconds(20));

        List<MinuteCandle> closed = aggregator.closeDue(M0.plusSeconds(60).plus(GRACE));

        assertThat(closed).extracting(MinuteCandle::symbol).containsExactly("KRW-BTC", "KRW-ETH");
    }

    @Test
    @DisplayName("연결 시작 시점 이전 분의 체결은 쓰지 않고, 진행 중이던 봉은 버린다")
    void startLiveDiscardsPartialMinutes() {
        aggregator.add(trade("KRW-BTC", "100", "1", M0.plusSeconds(10)), M0.plusSeconds(10));
        Instant liveFrom = M0.plusSeconds(60);

        aggregator.startLive(liveFrom);

        // 버려진 12:00봉은 마감 대상이 아니다
        assertThat(aggregator.closeDue(M0.plusSeconds(120))).isEmpty();
        // 12:00 체결은 무시, 12:01 체결부터 받는다
        assertThat(aggregator.add(trade("KRW-BTC", "100", "1", M0.plusSeconds(50)), M0.plusSeconds(61))).isFalse();
        assertThat(aggregator.add(trade("KRW-BTC", "100", "1", M0.plusSeconds(61)), M0.plusSeconds(61))).isTrue();
    }

    @Test
    @DisplayName("연결이 끊기면 진행 중인 봉을 버려서 일부 체결만 담은 봉이 저장되지 않는다")
    void discardOpenCandlesOnDisconnect() {
        aggregator.add(trade("KRW-BTC", "100", "1", M0.plusSeconds(10)), M0.plusSeconds(10));

        aggregator.discardOpenCandles();

        assertThat(aggregator.closeDue(M0.plusSeconds(300))).isEmpty();
    }

    private Trade trade(String symbol, String price, String volume, Instant tradeTimestamp) {
        return new Trade(symbol, new BigDecimal(price), new BigDecimal(volume), tradeTimestamp);
    }
}
