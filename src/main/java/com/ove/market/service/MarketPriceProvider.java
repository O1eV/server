package com.ove.market.service;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * 모의 거래에 필요한 서버 측 최신가 제공자.
 */
public interface MarketPriceProvider {

    /**
     * 거래소에 실제로 존재하는 종목인지 확인한다.
     * 없으면 MARKET_NOT_FOUND, 거래소 장애로 확인할 수 없으면 MARKET_PRICE_UNAVAILABLE을 던진다.
     */
    void validateMarket(String symbol);

    /**
     * 주문 체결에 쓸 최신가를 조회한다. 조회할 수 없으면 MARKET_PRICE_UNAVAILABLE을 던진다.
     */
    BigDecimal getLatestPrice(String symbol);

    /**
     * 포트폴리오 평가에 필요한 여러 종목의 최신가를 한 번에 조회한다.
     * 일부 종목(상장폐지·거래 중단 등)의 시세를 못 구하면 그 종목만 결과에서 빠지고, 전체 조회는 실패하지 않는다.
     */
    Map<String, BigDecimal> getPortfolioPrices(List<String> symbols);
}
