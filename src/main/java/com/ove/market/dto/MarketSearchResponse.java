package com.ove.market.dto;

import com.ove.market.client.UpbitMarketResponse;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Schema(description = "종목 검색 결과 한 건")
@Getter
@NoArgsConstructor
public class MarketSearchResponse {

    @Schema(description = "종목 심볼", example = "KRW-BTC")
    private String symbol;
    @Schema(description = "한글 이름", example = "비트코인")
    private String koreanName;
    @Schema(description = "영어 이름", example = "Bitcoin")
    private String englishName;
    @Schema(description = "자산군(현재 COIN만)", example = "COIN")
    private String assetClass;

    // 업비트 종목 정보로 검색 결과를 만들 때 사용
    public MarketSearchResponse(UpbitMarketResponse market) {
        this.symbol = market.getMarket();
        this.koreanName = market.getKoreanName();
        this.englishName = market.getEnglishName();
        this.assetClass = "COIN";
    }
}
