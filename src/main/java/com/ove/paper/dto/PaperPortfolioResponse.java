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
    // 보유 종목 모두의 최신 시세를 구했는지. false면 일부 종목을 평균 매수가로 평가한 것이다.
    private final boolean pricesComplete;
    private final List<PaperHoldingResponse> holdings;

    public static PaperPortfolioResponse from(PaperAccount account, List<PaperHolding> holdings,
                                              Map<String, BigDecimal> currentPrices) {
        BigDecimal totalPurchaseAmount = holdings.stream()
                .map(holding -> holding.getQuantity().multiply(holding.getAveragePurchasePrice()))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalEvaluationAmount = holdings.stream()
                .map(holding -> holding.getQuantity().multiply(valuationPrice(holding, currentPrices)))
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
                .pricesComplete(holdings.stream()
                        .allMatch(holding -> currentPrices.get(holding.getSymbol()) != null))
                .holdings(holdings.stream()
                        .map(holding -> PaperHoldingResponse.from(holding, currentPrices.get(holding.getSymbol())))
                        .toList())
                .build();
    }

    // 시세가 없는 종목은 평균 매수가로 평가해 총액과 보유 종목별 금액이 서로 맞도록 한다.
    private static BigDecimal valuationPrice(PaperHolding holding, Map<String, BigDecimal> currentPrices) {
        BigDecimal currentPrice = currentPrices.get(holding.getSymbol());
        return currentPrice != null ? currentPrice : holding.getAveragePurchasePrice();
    }
}
