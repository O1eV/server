package com.ove.paper.dto;

import java.math.BigDecimal;

import com.ove.paper.domain.PaperHolding;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class PaperHoldingResponse {

    private final String symbol;
    private final BigDecimal quantity;
    private final BigDecimal averagePurchasePrice;
    private final BigDecimal purchaseAmount;
    private final BigDecimal currentPrice;
    private final BigDecimal evaluationAmount;
    private final BigDecimal profitLoss;
    private final BigDecimal returnRate;

    public static PaperHoldingResponse from(PaperHolding holding, BigDecimal currentPrice) {
        BigDecimal purchaseAmount = holding.getQuantity().multiply(holding.getAveragePurchasePrice());
        BigDecimal evaluationAmount = holding.getQuantity().multiply(currentPrice);
        BigDecimal profitLoss = evaluationAmount.subtract(purchaseAmount);
        return PaperHoldingResponse.builder()
                .symbol(holding.getSymbol())
                .quantity(holding.getQuantity())
                .averagePurchasePrice(holding.getAveragePurchasePrice())
                .purchaseAmount(purchaseAmount)
                .currentPrice(currentPrice)
                .evaluationAmount(evaluationAmount)
                .profitLoss(profitLoss)
                .returnRate(PaperValuation.returnRate(profitLoss, purchaseAmount))
                .build();
    }
}
