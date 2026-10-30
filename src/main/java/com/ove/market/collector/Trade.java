package com.ove.market.collector;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 업비트 체결 한 건. tradeTimestamp는 거래소가 찍은 체결 시각(UTC)이다.
 */
public record Trade(String symbol, BigDecimal price, BigDecimal volume, Instant tradeTimestamp, BigDecimal changeRate) {
    public Trade(String symbol, BigDecimal price, BigDecimal volume, Instant tradeTimestamp) {
        this(symbol, price, volume, tradeTimestamp, null);
    }
}
