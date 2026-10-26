package com.ove.watchlist.dto;

import java.math.BigDecimal;
import java.time.Instant;

import lombok.Builder;
import lombok.Getter;

/**
 * 관심 종목 한 건. 현재가를 못 가져오면 price·koreanName은 null이고,
 * AI 서버가 아직 없어 signal·confidence는 항상 null이다.
 */
@Getter
@Builder
public class WatchlistItemResponse {

    private final String symbol;
    private final String koreanName;
    private final BigDecimal price;
    private final String signal;
    private final BigDecimal confidence;
    private final Instant addedAt;
}
