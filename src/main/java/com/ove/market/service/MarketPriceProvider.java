package com.ove.market.service;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * 모의 거래에 필요한 서버 측 최신가 제공자.
 */
public interface MarketPriceProvider {

    BigDecimal getLatestPrice(String symbol);

    /**
     * 포트폴리오 평가에 필요한 여러 종목의 최신가를 한 번에 조회한다.
     */
    Map<String, BigDecimal> getLatestPrices(List<String> symbols);
}
