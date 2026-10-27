package com.ove.paper.dto;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** 모의 포트폴리오의 평가 손익과 수익률을 계산한다. */
final class PaperValuation {

    private static final int RATE_SCALE = 4;
    private static final BigDecimal PERCENT = new BigDecimal("100");

    private PaperValuation() {
    }

    static BigDecimal returnRate(BigDecimal profitLoss, BigDecimal purchaseAmount) {
        if (purchaseAmount.signum() == 0) {
            return BigDecimal.ZERO.setScale(RATE_SCALE, RoundingMode.HALF_UP);
        }
        return profitLoss.multiply(PERCENT).divide(purchaseAmount, RATE_SCALE, RoundingMode.HALF_UP);
    }
}
