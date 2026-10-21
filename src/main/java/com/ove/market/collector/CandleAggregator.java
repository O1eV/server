package com.ove.market.collector;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import lombok.extern.slf4j.Slf4j;

/**
 * 체결을 UTC 1분봉으로 묶는다. 시간은 밖에서 넘겨받아서(now) 시계에 의존하지 않는다.
 *
 * - 봉 시각 = trade_timestamp를 UTC 분 단위로 내림
 * - 시가 = 가장 이른 체결, 종가 = 가장 늦은 체결(체결 시각 기준), 거래량 = 수량 합
 * - 봉은 그 분이 끝난 뒤 마감 유예(grace)가 지나야 마감한다. 유예 안에 도착한 늦은 체결은 봉에 들어간다.
 * - 마감 뒤에 늦게 온 체결은 무시한다. 체결이 없던 분은 봉을 만들지 않는다(업비트와 같다).
 */
@Slf4j
public class CandleAggregator {

    private final Duration grace;
    private final Map<Key, OpenCandle> openCandles = new HashMap<>();
    private final Map<String, Instant> lastClosedMinute = new HashMap<>();
    private Instant liveFrom = Instant.MIN;

    public CandleAggregator(Duration grace) {
        this.grace = grace;
    }

    /**
     * 체결을 받아 봉에 반영한다. 봉에 들어가면 true, 무시하면 false.
     */
    public synchronized boolean add(Trade trade, Instant now) {
        Instant minute = trade.tradeTimestamp().truncatedTo(ChronoUnit.MINUTES);

        // 연결하기 전부터 진행 중이던 분은 앞부분 체결을 못 받았으므로 REST 보충에 맡기고 쓰지 않는다.
        if (minute.isBefore(liveFrom)) {
            return false;
        }

        Instant lastClosed = lastClosedMinute.get(trade.symbol());
        boolean alreadyClosed = lastClosed != null && !minute.isAfter(lastClosed);
        boolean graceExpired = !now.isBefore(minute.plus(1, ChronoUnit.MINUTES).plus(grace));
        if (alreadyClosed || graceExpired) {
            log.warn("마감 뒤에 도착한 체결을 무시함: symbol={}, minute={}, tradeTimestamp={}, now={}",
                    trade.symbol(), minute, trade.tradeTimestamp(), now);
            return false;
        }

        openCandles.computeIfAbsent(new Key(trade.symbol(), minute), key -> new OpenCandle(trade)).add(trade);
        return true;
    }

    /**
     * 분이 끝나고 마감 유예가 지난 봉을 모두 마감해서 시간순으로 돌려준다.
     */
    public synchronized List<MinuteCandle> closeDue(Instant now) {
        List<MinuteCandle> closed = new ArrayList<>();
        Iterator<Map.Entry<Key, OpenCandle>> iterator = openCandles.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<Key, OpenCandle> entry = iterator.next();
            Key key = entry.getKey();
            if (now.isBefore(key.minute().plus(1, ChronoUnit.MINUTES).plus(grace))) {
                continue;
            }
            closed.add(entry.getValue().toCandle(key));
            lastClosedMinute.merge(key.symbol(), key.minute(), (a, b) -> a.isAfter(b) ? a : b);
            iterator.remove();
        }
        closed.sort(Comparator.comparing(MinuteCandle::time).thenComparing(MinuteCandle::symbol));
        return closed;
    }

    /**
     * 연결(재연결)을 시작할 때 호출한다. 진행 중이던 봉은 중간이 빠졌을 수 있어 버리고,
     * liveFrom보다 이른 분의 체결은 앞으로 받지 않는다. 그 구간은 REST 보충이 채운다.
     */
    public synchronized void startLive(Instant liveFrom) {
        this.openCandles.clear();
        this.liveFrom = liveFrom;
    }

    /**
     * 연결이 끊겼을 때 호출한다. 끊긴 동안 진행 중인 봉이 일부 체결만 담은 채 마감되지 않게 버린다.
     */
    public synchronized void discardOpenCandles() {
        this.openCandles.clear();
    }

    private record Key(String symbol, Instant minute) {
    }

    private static final class OpenCandle {

        private BigDecimal open;
        private Instant openAt;
        private BigDecimal high;
        private BigDecimal low;
        private BigDecimal close;
        private Instant closeAt;
        private BigDecimal volume = BigDecimal.ZERO;

        OpenCandle(Trade first) {
            this.open = first.price();
            this.openAt = first.tradeTimestamp();
            this.high = first.price();
            this.low = first.price();
            this.close = first.price();
            this.closeAt = first.tradeTimestamp();
        }

        // 도착 순서가 아니라 체결 시각 기준으로 시가·종가를 정한다(같은 시각이면 시가는 먼저, 종가는 나중 도착).
        void add(Trade trade) {
            if (trade.tradeTimestamp().isBefore(openAt)) {
                open = trade.price();
                openAt = trade.tradeTimestamp();
            }
            if (!trade.tradeTimestamp().isBefore(closeAt)) {
                close = trade.price();
                closeAt = trade.tradeTimestamp();
            }
            high = high.max(trade.price());
            low = low.min(trade.price());
            volume = volume.add(trade.volume());
        }

        MinuteCandle toCandle(Key key) {
            return new MinuteCandle(key.symbol(), key.minute(), open, high, low, close, volume);
        }
    }
}
