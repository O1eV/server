package com.ove.market.collector;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 1분봉. time은 봉이 시작하는 UTC 분(업비트 candle_date_time_utc와 같은 기준)이다.
 */
public record MinuteCandle(String symbol, Instant time, BigDecimal open, BigDecimal high, BigDecimal low,
                           BigDecimal close, BigDecimal volume) {
}
