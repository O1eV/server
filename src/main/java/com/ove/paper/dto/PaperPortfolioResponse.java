package com.ove.paper.dto;

import java.math.BigDecimal;
import java.util.List;

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
    private final BigDecimal totalAssetAmount;
    private final List<PaperHoldingResponse> holdings;

    /**
     * 시세 모듈이 아직 없으므로 보유자산 평가는 평균 매수가를 사용한다.
     * 최신가 연동 시 totalPurchaseAmount와 별도로 평가 금액·수익률을 내려줄 수 있다.
     */
    public static PaperPortfolioResponse from(PaperAccount account, List<PaperHolding> holdings) {
        BigDecimal totalPurchaseAmount = holdings.stream()
                .map(holding -> holding.getQuantity().multiply(holding.getAveragePurchasePrice()))
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        return PaperPortfolioResponse.builder()
                .currency(account.getCurrency())
                .cashBalance(account.getBalance())
                .totalPurchaseAmount(totalPurchaseAmount)
                .totalAssetAmount(account.getBalance().add(totalPurchaseAmount))
                .holdings(holdings.stream().map(PaperHoldingResponse::from).toList())
                .build();
    }
}
