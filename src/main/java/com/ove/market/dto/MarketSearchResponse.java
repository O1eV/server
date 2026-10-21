package com.ove.market.dto;

import com.ove.market.client.UpbitMarketResponse;

import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
public class MarketSearchResponse {

    private String symbol;
    private String koreanName;
    private String englishName;
    private String assetClass;

    // 업비트 종목 정보로 검색 결과를 만들 때 사용
    public MarketSearchResponse(UpbitMarketResponse market) {
        this.symbol = market.getMarket();
        this.koreanName = market.getKoreanName();
        this.englishName = market.getEnglishName();
        this.assetClass = "COIN";
    }
}
