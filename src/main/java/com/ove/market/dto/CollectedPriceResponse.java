package com.ove.market.dto;

import java.math.BigDecimal;
import java.time.Instant;

public record CollectedPriceResponse(String symbol, BigDecimal price, Instant tradeTimestamp,
                                     Instant updatedAt, boolean stale) {}
