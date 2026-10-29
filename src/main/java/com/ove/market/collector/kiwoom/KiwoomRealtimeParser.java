package com.ove.market.collector.kiwoom;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import com.ove.market.collector.Trade;

import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.JsonNode;

/**
 * 키움 실시간 REAL 메시지 해석(순수 로직). 0B(주식체결)는 Trade로, 0s(장시작시간)는 장 운영 구분 코드로 바꾼다.
 *
 * 0B 값(공식 스펙 기준):
 *  - 20 체결시간 HHmmss (KST, 날짜 없음)
 *  - 10 현재가: 부호가 붙은 숫자(전일 대비 방향 표시) → 절대값
 *  - 15 거래량: +는 매수체결, -는 매도체결 → 절대값 (1주 단위)
 *  - 290 장구분: 1 장전 시간외, 2 장중, 3 장후 시간외 → 장중(2)만 쓴다
 */
@Slf4j
public final class KiwoomRealtimeParser {

    private static final DateTimeFormatter HHMMSS = DateTimeFormatter.ofPattern("HHmmss");
    // 정규장 체결 시각 범위(KST). 15:30의 종가 단일가 체결까지 포함하려고 15:30:59까지 받는다.
    private static final LocalTime REGULAR_OPEN = LocalTime.of(9, 0, 0);
    private static final LocalTime REGULAR_LAST = LocalTime.of(15, 30, 59);

    private KiwoomRealtimeParser() {
    }

    /**
     * REAL 메시지 안의 0B 체결들을 Trade 목록으로 바꾼다. 장중이 아닌 체결, 구독하지 않은 종목, 값이 이상한 체결은 건너뛴다.
     *
     * @param kstDate 체결시간(HHmmss)에 붙일 오늘 날짜(KST). 메시지에 날짜가 없어서 수신 시각에서 구한다.
     */
    public static List<Trade> parseTrades(JsonNode message, LocalDate kstDate, Set<String> subscribedSymbols) {
        List<Trade> trades = new ArrayList<>();
        for (JsonNode item : message.path("data")) {
            if (!"0B".equals(item.path("type").asString(""))) {
                continue;
            }
            String symbol = item.path("item").asString("");
            if (!subscribedSymbols.contains(symbol)) {
                continue;
            }
            JsonNode values = flatten(item.path("values"));
            Trade trade = toTrade(symbol, values, kstDate);
            if (trade != null) {
                trades.add(trade);
            }
        }
        return trades;
    }

    /**
     * REAL 메시지 안의 0s(장시작시간) 장운영구분(215) 코드. 없으면 null.
     * 0: 장시작전 알림(8:40~), 3: 장시작(09:00), 2: 장마감 알림(15:20~), 4: 장마감(15:30),
     * 8: 정규장마감, 9: 전체장마감, a~e: 시간외 등
     */
    public static String marketOperationCode(JsonNode message) {
        for (JsonNode item : message.path("data")) {
            if ("0s".equals(item.path("type").asString(""))) {
                String code = flatten(item.path("values")).path("215").asString("");
                if (!code.isEmpty()) {
                    return code;
                }
            }
        }
        return null;
    }

    private static Trade toTrade(String symbol, JsonNode values, LocalDate kstDate) {
        // 장구분이 오면 장중(2)만 쓴다. 값이 비어 있으면 시각 범위로만 거른다.
        String session = values.path("290").asString("");
        if (!session.isEmpty() && !"2".equals(session)) {
            return null;
        }
        try {
            LocalTime time = LocalTime.parse(values.path("20").asString(""), HHMMSS);
            if (time.isBefore(REGULAR_OPEN) || time.isAfter(REGULAR_LAST)) {
                return null;
            }
            BigDecimal price = parseAbsolute(values.path("10").asString(""));
            BigDecimal volume = parseAbsolute(values.path("15").asString(""));
            if (price.signum() <= 0 || volume.signum() <= 0) {
                return null;
            }
            return new Trade(symbol, price, volume,
                    kstDate.atTime(time).atZone(KiwoomTokenProvider.KST).toInstant(),
                    parseChangeRate(values.path("12").asString("")));
        } catch (DateTimeParseException | NumberFormatException e) {
            log.warn("해석할 수 없는 키움 체결 값을 건너뜀: symbol={}", symbol);
            return null;
        }
    }

    // FID 12는 전일 대비 백분율이다. 부호를 보존하고 API에서는 소수 비율로 통일한다.
    private static BigDecimal parseChangeRate(String value) {
        try {
            return value.isBlank() ? null : new BigDecimal(value.trim()).movePointLeft(2);
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    // "+82", "-20800" 같은 부호 붙은 숫자를 절대값으로 바꾼다
    static BigDecimal parseAbsolute(String signed) {
        String trimmed = signed.trim();
        if (trimmed.startsWith("+")) {
            trimmed = trimmed.substring(1);
        }
        return new BigDecimal(trimmed).abs();
    }

    // values는 공식 예시에서는 객체지만 스펙 표에서는 LIST라서, 배열로 오면 객체들을 하나로 합쳐서 읽는다.
    private static JsonNode flatten(JsonNode values) {
        if (!values.isArray()) {
            return values;
        }
        tools.jackson.databind.node.ObjectNode merged = tools.jackson.databind.node.JsonNodeFactory.instance
                .objectNode();
        for (JsonNode element : values) {
            if (element.isObject()) {
                merged.setAll((tools.jackson.databind.node.ObjectNode) element);
            }
        }
        return merged;
    }
}
