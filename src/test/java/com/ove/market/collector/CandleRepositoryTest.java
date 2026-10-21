package com.ove.market.collector;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import com.ove.support.CollectorContainers;

/**
 * 실제 TimescaleDB(Testcontainers)에서 candles 테이블·hypertable·upsert 동작을 확인한다. Docker 필요.
 */
class CandleRepositoryTest {

    private static final Instant M0 = Instant.parse("2026-10-20T12:00:00Z");

    private static JdbcTemplate jdbcTemplate;
    private static CandleRepository repository;

    @BeforeAll
    static void setUpDatabase() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(CollectorContainers.TIMESCALE.getJdbcUrl(),
                CollectorContainers.TIMESCALE.getUsername(), CollectorContainers.TIMESCALE.getPassword());
        jdbcTemplate = new JdbcTemplate(dataSource);
        repository = new CandleRepository(jdbcTemplate);
        repository.initSchema();
    }

    @BeforeEach
    @AfterEach
    void cleanTable() {
        // 컨테이너를 다른 테스트 클래스와 같이 쓰므로 시작할 때와 끝날 때 모두 비운다.
        jdbcTemplate.update("DELETE FROM candles");
    }

    @Test
    @DisplayName("initSchema는 여러 번 불러도 되고, candles는 time 기준 hypertable이다")
    void initSchemaIsIdempotentAndCreatesHypertable() {
        repository.initSchema();
        repository.initSchema();

        Integer hypertables = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM timescaledb_information.hypertables WHERE hypertable_name = 'candles'",
                Integer.class);
        String partitionColumn = jdbcTemplate.queryForObject(
                "SELECT column_name FROM timescaledb_information.dimensions WHERE hypertable_name = 'candles'",
                String.class);
        assertThat(hypertables).isEqualTo(1);
        assertThat(partitionColumn).isEqualTo("time");
    }

    @Test
    @DisplayName("(symbol, time)이 기본키라서 같은 봉을 두 번 넣을 수 없다")
    void primaryKeyIsSymbolAndTime() {
        List<String> keyColumns = jdbcTemplate.queryForList("""
                SELECT a.attname FROM pg_index i
                JOIN pg_attribute a ON a.attrelid = i.indrelid AND a.attnum = ANY (i.indkey)
                WHERE i.indrelid = 'candles'::regclass AND i.indisprimary
                """, String.class);

        assertThat(keyColumns).containsExactlyInAnyOrder("symbol", "time");
    }

    @Test
    @DisplayName("upsert: 없으면 넣고, 같은 (symbol, time)이 있으면 값을 덮어쓴다")
    void upsertInsertsThenOverwrites() {
        repository.upsert(candle("KRW-BTC", M0, "100", "120", "90", "110", "5"));
        repository.upsert(candle("KRW-BTC", M0, "101", "130", "80", "111", "6.5"));

        List<Map<String, Object>> rows = jdbcTemplate.queryForList("SELECT * FROM candles");
        assertThat(rows).hasSize(1);
        Map<String, Object> row = rows.get(0);
        assertThat((BigDecimal) row.get("open")).isEqualByComparingTo("101");
        assertThat((BigDecimal) row.get("high")).isEqualByComparingTo("130");
        assertThat((BigDecimal) row.get("low")).isEqualByComparingTo("80");
        assertThat((BigDecimal) row.get("close")).isEqualByComparingTo("111");
        assertThat((BigDecimal) row.get("volume")).isEqualByComparingTo("6.5");
    }

    @Test
    @DisplayName("insertIfAbsent: 없으면 넣고 true, 이미 있으면 건드리지 않고 false")
    void insertIfAbsentDoesNotOverwrite() {
        boolean first = repository.insertIfAbsent(candle("KRW-BTC", M0, "100", "120", "90", "110", "5"));
        boolean second = repository.insertIfAbsent(candle("KRW-BTC", M0, "999", "999", "999", "999", "999"));

        assertThat(first).isTrue();
        assertThat(second).isFalse();
        Map<String, Object> row = jdbcTemplate.queryForMap("SELECT * FROM candles");
        assertThat((BigDecimal) row.get("open")).isEqualByComparingTo("100");
        assertThat((BigDecimal) row.get("volume")).isEqualByComparingTo("5");
    }

    @Test
    @DisplayName("같은 시각이라도 종목이 다르면 별개의 봉이다")
    void symbolsAreSeparate() {
        repository.upsert(candle("KRW-BTC", M0, "100", "100", "100", "100", "1"));
        repository.upsert(candle("KRW-ETH", M0, "50", "50", "50", "50", "1"));

        Integer count = jdbcTemplate.queryForObject("SELECT count(*) FROM candles", Integer.class);
        assertThat(count).isEqualTo(2);
    }

    @Test
    @DisplayName("시각은 UTC 그대로 저장된다")
    void timeIsStoredAsUtc() {
        repository.upsert(candle("KRW-BTC", M0, "1", "1", "1", "1", "1"));

        Timestamp stored = jdbcTemplate.queryForObject("SELECT time FROM candles", Timestamp.class);
        assertThat(stored.toInstant()).isEqualTo(M0);
    }

    @Test
    @DisplayName("소수점 8자리 거래량과 큰 가격이 반올림 없이 저장된다")
    void keepsPrecision() {
        repository.upsert(candle("KRW-BTC", M0, "150000000.5", "150000000.5", "150000000.5", "150000000.5",
                "0.00000001"));

        Map<String, Object> row = jdbcTemplate.queryForMap("SELECT * FROM candles");
        assertThat((BigDecimal) row.get("open")).isEqualByComparingTo("150000000.5");
        assertThat((BigDecimal) row.get("volume")).isEqualByComparingTo("0.00000001");
    }

    private MinuteCandle candle(String symbol, Instant time, String open, String high, String low, String close,
                                String volume) {
        return new MinuteCandle(symbol, time, new BigDecimal(open), new BigDecimal(high), new BigDecimal(low),
                new BigDecimal(close), new BigDecimal(volume));
    }
}
