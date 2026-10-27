package com.ove.market.collector.kiwoom;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.ove.market.collector.CandleBackfillSource;
import com.ove.market.collector.MinuteCandle;

import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.JsonNode;

/**
 * 키움 주식분봉차트조회(ka10080)로 재접속 뒤 빠진 1분봉을 채운다. 시세 조회 TR만 쓴다.
 *
 * 응답은 최신순이고 cntr_tm(체결시간, KST yyyyMMddHHmmss)이 봉의 시작인지 끝인지는 공식 자료에 없다.
 * 그래서 사용자가 확인해서 minute-bar-label(START/END)로 정해 줘야 쓸 수 있다(설정이 없으면 시작할 때 막는다).
 * 한 번에 몇 건을 주는지도 문서에 없어서, 연속조회(cont-yn/next-key)로 창의 시작까지 이어서 받는다(최대 MAX_PAGES쪽).
 */
@Slf4j
public class KiwoomChartBackfillSource implements CandleBackfillSource {

    private static final int MAX_PAGES = 10;
    private static final DateTimeFormatter CNTR_TM = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private static final DateTimeFormatter BASE_DT = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final LocalTime REGULAR_OPEN = LocalTime.of(9, 0);
    private static final LocalTime REGULAR_LAST_BAR = LocalTime.of(15, 30);

    private final KiwoomRestClient restClient;
    private final KiwoomTokenProvider tokenProvider;
    private final String adjustedPriceType;
    private final boolean labelIsBarEnd;

    public KiwoomChartBackfillSource(KiwoomRestClient restClient, KiwoomTokenProvider tokenProvider,
                                     KiwoomProperties properties) {
        this.restClient = restClient;
        this.tokenProvider = tokenProvider;
        this.adjustedPriceType = properties.adjustedPriceType();
        this.labelIsBarEnd = properties.minuteBarLabelIsEnd();
    }

    @Override
    public List<MinuteCandle> fetchRecent(String symbol, Instant liveFrom, int windowMinutes) {
        Instant target = liveFrom.minus(1, ChronoUnit.MINUTES);
        Instant windowStart = target.minus(windowMinutes - 1L, ChronoUnit.MINUTES);
        String baseDt = target.atZone(KiwoomTokenProvider.KST).format(BASE_DT);

        List<MinuteCandle> candles = new ArrayList<>();
        String nextKey = null;
        for (int page = 0; page < MAX_PAGES; page++) {
            KiwoomRestClient.KiwoomResponse response = call(symbol, baseDt, nextKey);
            Instant oldest = null;
            for (JsonNode row : response.body().path("stk_min_pole_chart_qry")) {
                MinuteCandle candle = toCandle(symbol, row);
                if (candle == null) {
                    continue;
                }
                candles.add(candle);
                if (oldest == null || candle.time().isBefore(oldest)) {
                    oldest = candle.time();
                }
            }
            if (!response.hasNext() || oldest == null || oldest.isBefore(windowStart)) {
                break;
            }
            nextKey = response.nextKey();
        }
        return candles;
    }

    private KiwoomRestClient.KiwoomResponse call(String symbol, String baseDt, String nextKey) {
        Map<String, Object> body = Map.of("stk_cd", symbol, "tic_scope", "1", "upd_stkpc_tp", adjustedPriceType,
                "base_dt", baseDt);
        try {
            return restClient.post("ka10080", "/api/dostk/chart", tokenProvider.token(), body, nextKey);
        } catch (KiwoomException e) {
            if (e.isTokenProblem()) {
                tokenProvider.invalidate(); // 다음 시도에서 새 토큰을 받는다
            }
            throw e;
        }
    }

    private MinuteCandle toCandle(String symbol, JsonNode row) {
        try {
            ZonedDateTime label = LocalDateTime.parse(row.path("cntr_tm").asString(""), CNTR_TM)
                    .atZone(KiwoomTokenProvider.KST);
            // 라벨이 봉의 끝 시각이면 한 분 앞으로 당겨서 봉의 시작 시각으로 맞춘다
            Instant barStart = (labelIsBarEnd ? label.minusMinutes(1) : label).toInstant();
            LocalTime barStartLocal = barStart.atZone(KiwoomTokenProvider.KST).toLocalTime();
            // 정규장 봉만 쓴다(장전·시간외 봉은 실시간 집계와 같은 기준으로 제외)
            if (barStartLocal.isBefore(REGULAR_OPEN) || barStartLocal.isAfter(REGULAR_LAST_BAR)) {
                return null;
            }
            return new MinuteCandle(symbol, barStart,
                    KiwoomRealtimeParser.parseAbsolute(row.path("open_pric").asString("")),
                    KiwoomRealtimeParser.parseAbsolute(row.path("high_pric").asString("")),
                    KiwoomRealtimeParser.parseAbsolute(row.path("low_pric").asString("")),
                    KiwoomRealtimeParser.parseAbsolute(row.path("cur_prc").asString("")),
                    volume(row));
        } catch (DateTimeParseException | NumberFormatException e) {
            log.warn("해석할 수 없는 키움 분봉 행을 건너뜀: symbol={}", symbol);
            return null;
        }
    }

    private BigDecimal volume(JsonNode row) {
        return KiwoomRealtimeParser.parseAbsolute(row.path("trde_qty").asString(""));
    }
}
