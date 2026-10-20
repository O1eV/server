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

    public static PaperHoldingResponse from(PaperHolding holding) {
        return PaperHoldingResponse.builder()
                .symbol(holding.getSymbol())
                .quantity(holding.getQuantity())
                .averagePurchasePrice(holding.getAveragePurchasePrice())
                .purchaseAmount(holding.getQuantity().multiply(holding.getAveragePurchasePrice()))
                .build();
    }
}
