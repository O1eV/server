package com.ove.market.dto;

import java.math.BigDecimal;

import com.ove.market.client.UpbitMarketResponse;
import com.ove.market.client.UpbitTickerResponse;

import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
public class MarketResponse {

    private String symbol;
    private String koreanName;
    private String englishName;
    private String assetClass;
    private String marketState;
    private BigDecimal price;
    private BigDecimal changeRate;
    private String signal;
    private BigDecimal confidence;

    // 업비트 종목 정보와 현재가로 종목 목록의 한 줄을 만들 때 사용 (signal·confidence는 AI 연결 전이라 null)
    public MarketResponse(UpbitMarketResponse market, UpbitTickerResponse ticker) {
        this.symbol = market.getMarket();
        this.koreanName = market.getKoreanName();
        this.englishName = market.getEnglishName();
        this.assetClass = "COIN";
        this.marketState = ticker.getMarketState();
        this.price = ticker.getTradePrice();
        this.changeRate = ticker.getSignedChangeRate();
        this.signal = null;
        this.confidence = null;
    }
}
