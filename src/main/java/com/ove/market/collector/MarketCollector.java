package com.ove.market.collector;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import lombok.extern.slf4j.Slf4j;

/**
 * 시세 수집기. 체결을 받아 (1) 현재가와 tick을 Redis에 내보내고 (2) 1분봉으로 묶어 마감할 때
 * TimescaleDB에 저장하고 Redis로 알린다. 다시 연결될 때마다 최근 200분을 REST로 받아 빠진 봉을 채운다.
 * 체결 소스(업비트·키움)와 보충 소스는 밖에서 넣어 주므로 같은 클래스를 소스마다 한 개씩 만들어 쓴다.
 * 인스턴스 한 개만 실행한다고 가정한다(여러 대가 같은 봉을 동시에 쓰는 경우는 다루지 않는다).
 *
 * 메서드가 시각을 직접 받는 이유: 테스트에서 시간을 흘려 보내며 동작을 확인하기 위해서다.
 */
@Slf4j
public class MarketCollector implements TradeSource.TradeListener {

    // 재접속할 때마다 종목별로 최근 이만큼의 분을 다시 받아 빠진 봉을 채운다(업비트 분봉 REST는 한 번에 200개까지만 준다)
    private static final int BACKFILL_WINDOW_MINUTES = 200;

    // 로그에 찍을 소스 이름(업비트, 키움)
    private final String sourceName;
    private final List<String> symbols;
    private final CollectorProperties properties;
    private final TradeSource tradeSource;
    private final CandleAggregator aggregator;
    private final CandleRepository candleRepository;
    private final MarketRedisPublisher redisPublisher;
    // 재접속 때 빠진 봉을 채우는 소스. null이면 보충을 하지 않는다.
    private final CandleBackfillSource backfillSource;
    private final Clock clock;

    // 이미 예약했거나 끝낸 보충의 실시간 시작 시각. 같은 분 안에 연결이 여러 번 붙어도 보충은 한 번만 한다.
    private Instant claimedBackfill;

    // 봉 마감 확인과 누락 구간 보충을 실행하는 스레드 하나. 첫 작업을 낼 때 스레드가 만들어진다.
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "market-collector");
        thread.setDaemon(true);
        return thread;
    });

    public MarketCollector(String sourceName, List<String> symbols, CollectorProperties properties,
                           TradeSource tradeSource, CandleAggregator aggregator, CandleRepository candleRepository,
                           MarketRedisPublisher redisPublisher, CandleBackfillSource backfillSource, Clock clock) {
        this.sourceName = sourceName;
        this.symbols = List.copyOf(symbols);
        this.properties = properties;
        this.tradeSource = tradeSource;
        this.aggregator = aggregator;
        this.candleRepository = candleRepository;
        this.redisPublisher = redisPublisher;
        this.backfillSource = backfillSource;
        this.clock = clock;
    }

    public void start() {
        candleRepository.initSchema();
        long intervalMillis = properties.tickCheckInterval().toMillis();
        scheduler.scheduleWithFixedDelay(() -> tick(clock.instant()), intervalMillis, intervalMillis,
                TimeUnit.MILLISECONDS);
        tradeSource.start(this);
        log.info("{} 시세 수집기 시작: symbols={}", sourceName, symbols);
    }

    public void stop() {
        tradeSource.stop();
        scheduler.shutdownNow();
    }

    @Override
    public void onConnected(Instant connectedAt) {
        // 접속한 분은 앞부분 체결을 못 받았을 수 있다. 시계 차이를 감안해 유예만큼 늦춘 시각이 속한 분까지는
        // 실시간 집계에서 빼고, 그 구간은 그 분이 끝난 뒤 REST로 채운다.
        Instant liveFrom = connectedAt.plus(properties.graceDelay()).truncatedTo(ChronoUnit.MINUTES)
                .plus(1, ChronoUnit.MINUTES);
        aggregator.startLive(liveFrom);

        if (backfillSource == null) {
            log.info("{} 연결됨: 실시간 집계는 {}분부터(누락 구간 보충 없음)", sourceName, liveFrom);
            return;
        }

        // 같은 분(같은 liveFrom)에는 보충 대상 구간이 같아서, 연결이 자주 끊겼다 붙어도 REST 호출이 늘지 않게 한 번만 한다.
        if (!claimBackfill(liveFrom)) {
            log.info("{} 연결됨: 실시간 집계는 {}분부터, 같은 분의 누락 구간 보충은 이미 처리 중이거나 끝나 건너뜀", sourceName, liveFrom);
            return;
        }

        Duration delay = Duration.between(clock.instant(), liveFrom.plus(properties.graceDelay()));
        log.info("{} 연결됨: 실시간 집계는 {}분부터, 누락 구간 보충은 {}ms 뒤", sourceName, liveFrom,
                Math.max(0, delay.toMillis()));
        scheduleBackfill(liveFrom, 1, Math.max(0, delay.toMillis()));
    }

    @Override
    public void onDisconnected() {
        // 끊긴 동안 진행 중이던 봉이 일부 체결만 담은 채 저장되지 않도록 버린다. 그 구간은 재연결 뒤 REST로 채운다.
        aggregator.discardOpenCandles();
        log.warn("{} 연결이 끊김: 진행 중이던 봉을 버림", sourceName);
    }

    @Override
    public void onTrade(Trade trade) {
        Instant now = clock.instant();
        try {
            aggregator.add(trade, now);
            redisPublisher.onTrade(trade, now);
        } catch (RuntimeException e) {
            // Redis 오류가 수신 스레드를 멈추지 않게 한다. 봉 집계는 이미 위에서 끝났다.
            log.warn("체결 처리 중 오류: symbol={}, cause={}", trade.symbol(), e.toString());
        }
    }

    /**
     * 주기적으로 불린다. 마감할 봉이 있으면 저장·발행하고, 미뤄 둔 tick을 발행한다.
     */
    void tick(Instant now) {
        try {
            for (MinuteCandle candle : aggregator.closeDue(now)) {
                closeCandle(candle);
            }
            redisPublisher.flushPendingTicks(now);
        } catch (RuntimeException e) {
            log.error("수집기 주기 작업 오류", e);
        }
    }

    private void closeCandle(MinuteCandle candle) {
        try {
            candleRepository.upsert(candle);
        } catch (RuntimeException e) {
            log.error("1분봉 저장 실패: symbol={}, time={}", candle.symbol(), candle.time(), e);
            return;
        }
        try {
            redisPublisher.publishCandleClosed(candle);
        } catch (RuntimeException e) {
            log.warn("1분봉 마감 발행 실패: symbol={}, time={}, cause={}", candle.symbol(), candle.time(), e.toString());
        }
    }

    private synchronized boolean claimBackfill(Instant liveFrom) {
        if (liveFrom.equals(claimedBackfill)) {
            return false;
        }
        claimedBackfill = liveFrom;
        return true;
    }

    private synchronized void releaseBackfill(Instant liveFrom) {
        if (liveFrom.equals(claimedBackfill)) {
            claimedBackfill = null;
        }
    }

    private void scheduleBackfill(Instant liveFrom, int attempt, long delayMillis) {
        try {
            scheduler.schedule(() -> runBackfill(liveFrom, attempt), delayMillis, TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException e) {
            // stop()으로 스케줄러가 닫힌 경우
        }
    }

    private void runBackfill(Instant liveFrom, int attempt) {
        try {
            backfill(liveFrom);
        } catch (RuntimeException e) {
            if (attempt < properties.backfillMaxAttempts()) {
                log.warn("누락 구간 보충 실패({}회차): {}ms 뒤 다시 시도, cause={}", attempt,
                        properties.backfillRetryDelay().toMillis(), e.toString());
                scheduleBackfill(liveFrom, attempt + 1, properties.backfillRetryDelay().toMillis());
            } else {
                log.error("누락 구간 보충을 포기함: {}회 모두 실패", attempt, e);
                // 같은 분에 다시 연결되면 한 번 더 시도할 수 있게 풀어 준다.
                releaseBackfill(liveFrom);
            }
        }
    }

    /**
     * 재접속할 때마다 종목별로 "실시간 집계 직전 분"까지 최근 200분을 REST 분봉으로 받아 빠진 봉을 채운다.
     * 호출은 종목당 1회다. 예전처럼 "DB의 마지막 봉 다음부터"만 보지 않기 때문에, 한 번 보충이 실패해서 구멍이 생겨도
     * 그 뒤에 실시간 봉이 저장됐더라도 다음 재접속에서 200분 안의 구멍은 다시 채워진다.
     *
     * 저장은 insertIfAbsent라서 이미 마감 저장된 봉은 덮어쓰지 않는다(REST 값보다 저장된 값을 우선한다).
     * 같은 봉을 매번 다시 받아도 안전하다. 실시간 집계가 맡는 분(liveFrom 이후)은 건드리지 않는다.
     * 보충한 봉은 "마감 알림"을 내지 않는다. 지난 구간이라 알림을 받아 AI를 호출하는 쪽에서 오래된 신호가 생기기 때문이다.
     * DB가 비어 있으면 같은 방식으로 최근 200분 이력이 한 번에 채워진다. 200분보다 오래 끊겼던 구간은 채우지 못한다.
     */
    void backfill(Instant liveFrom) {
        Instant target = liveFrom.minus(1, ChronoUnit.MINUTES);
        Instant windowStart = target.minus(BACKFILL_WINDOW_MINUTES - 1L, ChronoUnit.MINUTES);
        for (String symbol : symbols) {
            List<MinuteCandle> candles = backfillSource.fetchRecent(symbol, liveFrom, BACKFILL_WINDOW_MINUTES);
            int inserted = 0;
            int alreadyStored = 0;
            for (MinuteCandle candle : candles) {
                if (candle.time().isBefore(windowStart) || candle.time().isAfter(target)) {
                    continue;
                }
                if (candleRepository.insertIfAbsent(candle)) {
                    inserted++;
                } else {
                    alreadyStored++;
                }
            }
            log.info("누락 구간 보충: symbol={}, 범위={}~{}, 저장={}개, 이미 있음={}개", symbol, windowStart, target,
                    inserted, alreadyStored);
        }
    }
}
