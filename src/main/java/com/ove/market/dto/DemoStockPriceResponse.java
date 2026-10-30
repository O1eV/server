package com.ove.market.dto;

import java.math.BigDecimal;
import java.time.Instant;

/** changeRate는 전일 대비 소수 비율이며 수신하지 못한 경우 null이다. */
public record DemoStockPriceResponse(String symbol, BigDecimal price, Instant tradeTimestamp,
                                     Instant updatedAt, boolean stale, BigDecimal changeRate) {}
