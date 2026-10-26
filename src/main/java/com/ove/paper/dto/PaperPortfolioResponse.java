package com.ove.paper.dto;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import com.ove.account.domain.PaperAccount;
import com.ove.paper.domain.PaperHolding;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class PaperPortfolioResponse {

    private final String currency;
    private final BigDecimal cashBalance;
    private final BigDecimal totalPurchaseAmount;
    private final BigDecimal totalEvaluationAmount;
    private final BigDecimal totalProfitLoss;
    private final BigDecimal totalReturnRate;
    private final BigDecimal totalAssetAmount;
    private final List<PaperHoldingResponse> holdings;

    public static PaperPortfolioResponse from(PaperAccount account, List<PaperHolding> holdings,
                                              Map<String, BigDecimal> currentPrices) {
        BigDecimal totalPurchaseAmount = holdings.stream()
                .map(holding -> holding.getQuantity().multiply(holding.getAveragePurchasePrice()))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalEvaluationAmount = holdings.stream()
                .map(holding -> holding.getQuantity().multiply(currentPrices.get(holding.getSymbol())))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalProfitLoss = totalEvaluationAmount.subtract(totalPurchaseAmount);

        return PaperPortfolioResponse.builder()
                .currency(account.getCurrency())
                .cashBalance(account.getBalance())
                .totalPurchaseAmount(totalPurchaseAmount)
                .totalEvaluationAmount(totalEvaluationAmount)
                .totalProfitLoss(totalProfitLoss)
                .totalReturnRate(PaperValuation.returnRate(totalProfitLoss, totalPurchaseAmount))
                .totalAssetAmount(account.getBalance().add(totalEvaluationAmount))
                .holdings(holdings.stream()
                        .map(holding -> PaperHoldingResponse.from(holding, currentPrices.get(holding.getSymbol())))
                        .toList())
                .build();
    }
}
