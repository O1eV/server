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
    private String sign;
    private BigDecimal changePrice;
    private BigDecimal changeRate;
    private BigDecimal accTradePrice24h;

    // 업비트 종목 정보와 현재가로 종목 목록 응답을 만들 때 사용
    public MarketResponse(UpbitMarketResponse market, UpbitTickerResponse ticker) {
        this.symbol = market.getMarket();
        this.koreanName = market.getKoreanName();
        this.englishName = market.getEnglishName();
        this.assetClass = "COIN";
        this.marketState = market.getMarketState();
        this.price = ticker.getTradePrice();
        this.sign = ticker.getChange();
        this.changePrice = ticker.getSignedChangePrice();
        this.changeRate = ticker.getSignedChangeRate();
        this.accTradePrice24h = ticker.getAccTradePrice24h();
    }
}
