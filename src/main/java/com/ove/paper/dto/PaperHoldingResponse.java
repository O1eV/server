package com.ove.paper.dto;

import java.math.BigDecimal;

import com.ove.paper.domain.PaperHolding;

import lombok.Builder;
import lombok.Getter;

/**
 * 보유 종목 한 건. 최신 시세를 못 구하면 평균 매수가로 평가하고 priceAvailable=false로 알려 준다.
 * 화면에서는 priceAvailable=false인 종목에 "시세 없음" 같은 표시를 하면 된다.
 */
@Getter
@Builder
public class PaperHoldingResponse {

    private final String symbol;
    private final BigDecimal quantity;
    private final BigDecimal averagePurchasePrice;
    private final BigDecimal purchaseAmount;
    private final BigDecimal currentPrice;
    private final boolean priceAvailable;
    private final BigDecimal evaluationAmount;
    private final BigDecimal profitLoss;
    private final BigDecimal returnRate;

    public static PaperHoldingResponse from(PaperHolding holding, BigDecimal currentPrice) {
        BigDecimal purchaseAmount = holding.getQuantity().multiply(holding.getAveragePurchasePrice());
        // 시세가 없으면 전체 포트폴리오 조회를 실패시키지 않고 평균 매수가로 평가한다.
        boolean priceAvailable = currentPrice != null;
        BigDecimal valuationPrice = priceAvailable ? currentPrice : holding.getAveragePurchasePrice();
        BigDecimal evaluationAmount = holding.getQuantity().multiply(valuationPrice);
        BigDecimal profitLoss = evaluationAmount.subtract(purchaseAmount);
        return PaperHoldingResponse.builder()
                .symbol(holding.getSymbol())
                .quantity(holding.getQuantity())
                .averagePurchasePrice(holding.getAveragePurchasePrice())
                .purchaseAmount(purchaseAmount)
                .currentPrice(valuationPrice)
                .priceAvailable(priceAvailable)
                .evaluationAmount(evaluationAmount)
                .profitLoss(profitLoss)
                .returnRate(PaperValuation.returnRate(profitLoss, purchaseAmount))
                .build();
    }
}
