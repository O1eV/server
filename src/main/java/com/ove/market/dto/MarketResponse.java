package com.ove.market.dto;

import java.math.BigDecimal;

import com.ove.market.client.UpbitMarketResponse;
import com.ove.market.client.UpbitTickerResponse;
import com.ove.signal.dto.SignalSummary;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Schema(description = "종목 한 건(목록의 한 줄)")
@Getter
@NoArgsConstructor
public class MarketResponse {

    @Schema(description = "종목 심볼", example = "KRW-BTC")
    private String symbol;
    @Schema(description = "한글 이름", example = "비트코인")
    private String koreanName;
    @Schema(description = "영어 이름", example = "Bitcoin")
    private String englishName;
    @Schema(description = "자산군(현재 COIN만)", example = "COIN")
    private String assetClass;
    @Schema(description = "시장 상태. 전일 대비 변동률로 정합니다(|5%| 이상 VOLATILE, +1% 이상 UP, -1% 이하 DOWN, 그 외 SIDEWAYS)", example = "UP")
    private String marketState;
    @Schema(description = "현재가(KRW). 최대 1.5초 전 값", example = "111275000")
    private BigDecimal price;
    @Schema(description = "전일 대비 변동률(소수. 0.02 = +2%)", example = "0.0123")
    private BigDecimal changeRate;
    @Schema(description = "최신 AI 신호(BUY / HOLD / SELL). 비로그인이거나 신호가 없으면 null", example = "BUY")
    private String signal;
    @Schema(description = "최신 AI 신호의 확신도(0~1). 비로그인이거나 신호가 없으면 null", example = "0.71")
    private BigDecimal confidence;

    // 업비트 종목 정보와 현재가만으로 종목 목록의 한 줄을 만들 때 사용 (신호는 null)
    public MarketResponse(UpbitMarketResponse market, UpbitTickerResponse ticker) {
        this(market, ticker, null);
    }

    // 로그인 사용자의 종목 목록에 최신 신호 요약을 합칠 때 사용
    public MarketResponse(UpbitMarketResponse market, UpbitTickerResponse ticker, SignalSummary signal) {
        this.symbol = market.getMarket();
        this.koreanName = market.getKoreanName();
        this.englishName = market.getEnglishName();
        this.assetClass = "COIN";
        this.marketState = ticker.getMarketState();
        this.price = ticker.getTradePrice();
        this.changeRate = ticker.getSignedChangeRate();
        this.signal = signal == null ? null : signal.signal();
        this.confidence = signal == null ? null : signal.confidence();
    }
}
