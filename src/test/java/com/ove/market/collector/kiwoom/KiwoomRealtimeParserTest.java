package com.ove.market.collector.kiwoom;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.ove.market.collector.Trade;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class KiwoomRealtimeParserTest {

    private static final LocalDate DAY = LocalDate.of(2026, 10, 21);
    private static final Set<String> SYMBOLS = Set.of("005930", "000660");

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void preservesSignedDailyChangeRate() {
        for (String rate : List.of("+2.50", "-1.25", "0")) {
            Trade trade = parse(real("0B", "005930", "20", "100000", "10", "1000", "15", "1", "12", rate)).get(0);
            assertThat(trade.changeRate()).isEqualByComparingTo(new BigDecimal(rate).movePointLeft(2));
        }
        Trade missing = parse(real("0B", "005930", "20", "100000", "10", "1000", "15", "1")).get(0);
        assertThat(missing.changeRate()).isNull();
    }

    @Test
    @DisplayName("부호가 붙은 현재가·거래량은 절대값으로, 체결시간(KST)은 UTC로 바뀐다")
    void parsesSignedValuesAndKstTime() {
        List<Trade> trades = parse(real("0B", "005930", "20", "090015", "10", "-78800", "15", "+82", "290", "2"));

        assertThat(trades).hasSize(1);
        Trade trade = trades.get(0);
        assertThat(trade.symbol()).isEqualTo("005930");
        assertThat(trade.price()).isEqualByComparingTo("78800");
        assertThat(trade.volume()).isEqualByComparingTo("82");
        // 2026-10-21 09:00:15 KST = 00:00:15 UTC
        assertThat(trade.tradeTimestamp()).isEqualTo(Instant.parse("2026-10-21T00:00:15Z"));
    }

    @Test
    @DisplayName("매도 체결(거래량 부호 -)도 수량은 절대값, 현재가 부호(+)는 떼고 읽는다")
    void sellVolumeAndPlusPrice() {
        Trade trade = parse(real("0B", "005930", "20", "153000", "10", "+78800", "15", "-1200")).get(0);

        assertThat(trade.price()).isEqualByComparingTo("78800");
        assertThat(trade.volume()).isEqualByComparingTo("1200");
        // 15:30:00 KST = 06:30:00 UTC (종가 단일가 체결 시각)
        assertThat(trade.tradeTimestamp()).isEqualTo(Instant.parse("2026-10-21T06:30:00Z"));
    }

    @Test
    @DisplayName("장구분(290)이 장중(2)이 아니면 건너뛰고, 값이 없으면 시각 범위로만 거른다")
    void filtersBySessionField() {
        assertThat(parse(real("0B", "005930", "20", "100000", "10", "1000", "15", "1", "290", "1"))).isEmpty();
        assertThat(parse(real("0B", "005930", "20", "100000", "10", "1000", "15", "1", "290", "3"))).isEmpty();
        assertThat(parse(real("0B", "005930", "20", "100000", "10", "1000", "15", "1", "290", "2"))).hasSize(1);
        assertThat(parse(real("0B", "005930", "20", "100000", "10", "1000", "15", "1", "290", ""))).hasSize(1);
        assertThat(parse(real("0B", "005930", "20", "100000", "10", "1000", "15", "1"))).hasSize(1);
    }

    @Test
    @DisplayName("정규장 시각 범위(09:00:00 ~ 15:30:59)를 벗어난 체결은 건너뛴다")
    void filtersByTimeOfDay() {
        assertThat(parse(real("0B", "005930", "20", "085959", "10", "1000", "15", "1"))).isEmpty();
        assertThat(parse(real("0B", "005930", "20", "090000", "10", "1000", "15", "1"))).hasSize(1);
        assertThat(parse(real("0B", "005930", "20", "153059", "10", "1000", "15", "1"))).hasSize(1);
        assertThat(parse(real("0B", "005930", "20", "153100", "10", "1000", "15", "1"))).isEmpty();
        assertThat(parse(real("0B", "005930", "20", "160000", "10", "1000", "15", "1"))).isEmpty();
    }

    @Test
    @DisplayName("구독하지 않은 종목, 0B가 아닌 TR, 값이 이상한 체결은 건너뛴다")
    void skipsUnknownOrInvalid() {
        assertThat(parse(real("0B", "035720", "20", "100000", "10", "1000", "15", "1"))).isEmpty();
        assertThat(parse(real("0s", "005930", "20", "100000", "10", "1000", "15", "1"))).isEmpty();
        assertThat(parse(real("0B", "005930", "20", "10:00:00", "10", "1000", "15", "1"))).isEmpty();
        assertThat(parse(real("0B", "005930", "20", "100000", "10", "abc", "15", "1"))).isEmpty();
        assertThat(parse(real("0B", "005930", "20", "100000", "10", "0", "15", "1"))).isEmpty();
        assertThat(parse(real("0B", "005930", "20", "100000", "10", "1000", "15", "0"))).isEmpty();
        assertThat(parse(real("0B", "005930", "10", "1000", "15", "1"))).isEmpty(); // 체결시간 없음
        assertThat(parse(objectMapper.readTree("{\"trnm\":\"REAL\"}"))).isEmpty();
    }

    @Test
    @DisplayName("한 메시지에 여러 종목이 와도 각각 해석하고, values가 배열로 와도 읽는다")
    void multipleItemsAndArrayValues() {
        JsonNode message = objectMapper.readTree("""
                {"trnm":"REAL","data":[
                  {"type":"0B","item":"005930","values":{"20":"100000","10":"-78800","15":"+10"}},
                  {"type":"0B","item":"000660","values":[{"20":"100001"},{"10":"+210000"},{"15":"-3"}]}
                ]}""");

        List<Trade> trades = parse(message);

        assertThat(trades).extracting(Trade::symbol).containsExactly("005930", "000660");
        assertThat(trades.get(1).price()).isEqualByComparingTo(new BigDecimal("210000"));
        assertThat(trades.get(1).volume()).isEqualByComparingTo("3");
    }

    @Test
    @DisplayName("0s 메시지에서 장운영구분(215) 코드를 읽는다")
    void readsMarketOperationCode() {
        JsonNode open = objectMapper.readTree(
                "{\"trnm\":\"REAL\",\"data\":[{\"type\":\"0s\",\"item\":\"\",\"values\":{\"215\":\"3\",\"20\":\"090000\"}}]}");
        JsonNode close = objectMapper.readTree(
                "{\"trnm\":\"REAL\",\"data\":[{\"type\":\"0s\",\"item\":\"\",\"values\":{\"215\":\"b\",\"20\":\"160000\"}}]}");

        assertThat(KiwoomRealtimeParser.marketOperationCode(open)).isEqualTo("3");
        assertThat(KiwoomRealtimeParser.marketOperationCode(close)).isEqualTo("b");
        assertThat(KiwoomRealtimeParser.marketOperationCode(
                real("0B", "005930", "20", "100000", "10", "1000", "15", "1"))).isNull();
    }

    // 공식 스펙의 응답 예시와 같은 모양의 REAL 메시지를 만든다. kv는 키, 값 순서로 이어진 FID 목록
    private JsonNode real(String type, String item, String... kv) {
        StringBuilder values = new StringBuilder();
        for (int i = 0; i < kv.length; i += 2) {
            values.append(i == 0 ? "" : ",").append('"').append(kv[i]).append("\":\"").append(kv[i + 1]).append('"');
        }
        return objectMapper.readTree("{\"trnm\":\"REAL\",\"data\":[{\"type\":\"" + type + "\",\"name\":\"x\","
                + "\"item\":\"" + item + "\",\"values\":{" + values + "}}]}");
    }

    private List<Trade> parse(JsonNode message) {
        return KiwoomRealtimeParser.parseTrades(message, DAY, SYMBOLS);
    }
}
