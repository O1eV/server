package com.ove.market.collector;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;

/**
 * TimescaleDB candles 테이블 접근. 1분봉만 저장한다(5m·15m·1h·1d는 조회 때 time_bucket으로 집계할 예정).
 */
public class CandleRepository {

    private static final String UPSERT_SQL = """
            INSERT INTO candles (symbol, time, open, high, low, close, volume)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (symbol, time) DO UPDATE SET
                open = EXCLUDED.open, high = EXCLUDED.high, low = EXCLUDED.low,
                close = EXCLUDED.close, volume = EXCLUDED.volume
            """;

    private static final String INSERT_IF_ABSENT_SQL = """
            INSERT INTO candles (symbol, time, open, high, low, close, volume)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (symbol, time) DO NOTHING
            """;

    private final JdbcTemplate jdbcTemplate;

    public CandleRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * candles 테이블과 hypertable을 만든다. 몇 번을 불러도 같은 결과가 되도록 전부 IF NOT EXISTS로 쓴다.
     * (Flyway 같은 마이그레이션 도구는 아직 쓰지 않는다. 이유는 작업 보고 참고)
     */
    public void initSchema() {
        jdbcTemplate.execute("CREATE EXTENSION IF NOT EXISTS timescaledb");
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS candles (
                    symbol  VARCHAR(20)    NOT NULL,
                    time    TIMESTAMPTZ    NOT NULL,
                    open    NUMERIC(24, 8) NOT NULL,
                    high    NUMERIC(24, 8) NOT NULL,
                    low     NUMERIC(24, 8) NOT NULL,
                    close   NUMERIC(24, 8) NOT NULL,
                    volume  NUMERIC(28, 8) NOT NULL,
                    PRIMARY KEY (symbol, time)
                )
                """);
        jdbcTemplate.execute("SELECT create_hypertable('candles', 'time', if_not_exists => TRUE)");
    }

    /**
     * 실시간 집계로 마감한 봉을 저장한다. 같은 (symbol, time)이 있으면 덮어쓴다.
     */
    public void upsert(MinuteCandle candle) {
        write(UPSERT_SQL, candle);
    }

    /**
     * REST로 보충한 봉을 저장한다. 이미 있는 (symbol, time)은 건드리지 않는다.
     * 저장했으면 true, 이미 있어서 건너뛰었으면 false.
     */
    public boolean insertIfAbsent(MinuteCandle candle) {
        return write(INSERT_IF_ABSENT_SQL, candle) == 1;
    }

    /**
     * 저장된 1분봉을 조회한다(읽기 전용). to 이전(to는 포함하지 않음)부터 과거로 limit개를 가져오고,
     * from이 있으면 from 이후(from 포함)만 남긴다. 결과는 시간 오름차순이다.
     */
    public List<MinuteCandle> findMinuteCandles(String symbol, Instant from, Instant to, int limit) {
        StringBuilder sql = new StringBuilder("SELECT symbol, time, open, high, low, close, volume FROM candles "
                + "WHERE symbol = ? AND time < ?");
        List<Object> args = new ArrayList<>(List.of(symbol, OffsetDateTime.ofInstant(to, ZoneOffset.UTC)));
        if (from != null) {
            sql.append(" AND time >= ?");
            args.add(OffsetDateTime.ofInstant(from, ZoneOffset.UTC));
        }
        sql.append(" ORDER BY time DESC LIMIT ?");
        args.add(limit);
        List<MinuteCandle> candles = jdbcTemplate.query(sql.toString(),
                (rs, rowNum) -> new MinuteCandle(rs.getString("symbol"),
                        rs.getObject("time", OffsetDateTime.class).toInstant(), rs.getBigDecimal("open"),
                        rs.getBigDecimal("high"), rs.getBigDecimal("low"), rs.getBigDecimal("close"),
                        rs.getBigDecimal("volume")),
                args.toArray());
        Collections.reverse(candles);
        return candles;
    }

    private int write(String sql, MinuteCandle candle) {
        return jdbcTemplate.update(sql, candle.symbol(), OffsetDateTime.ofInstant(candle.time(), ZoneOffset.UTC),
                candle.open(), candle.high(), candle.low(), candle.close(), candle.volume());
    }
}
