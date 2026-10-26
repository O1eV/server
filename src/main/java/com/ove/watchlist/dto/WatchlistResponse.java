package com.ove.watchlist.dto;

import java.math.BigDecimal;
import java.time.Instant;

import com.ove.market.dto.MarketResponse;
import com.ove.watchlist.domain.Watchlist;

import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
public class WatchlistResponse {

    private String symbol;
    private String koreanName;
    private String englishName;
    private BigDecimal price;
    private BigDecimal changeRate;
    private String signal;
    private BigDecimal confidence;
    private Instant addedAt;

    // 관심 종목 한 줄과 그 종목의 현재가로 목록의 한 줄을 만들 때 사용 (업비트가 안 되면 market은 null이고 이름·가격이 비어 있다)
    public WatchlistResponse(Watchlist watchlist, MarketResponse market) {
        this.symbol = watchlist.getSymbol();
        this.addedAt = watchlist.getCreatedAt();
        if (market != null) {
            this.koreanName = market.getKoreanName();
            this.englishName = market.getEnglishName();
            this.price = market.getPrice();
            this.changeRate = market.getChangeRate();
            this.signal = market.getSignal();
            this.confidence = market.getConfidence();
        }
    }
}
