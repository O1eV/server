package com.ove.market.service;

import java.math.BigDecimal;

/**
 * 주문 체결에 필요한 서버 측 최신가 제공자.
 */
public interface MarketPriceProvider {

    BigDecimal getLatestPrice(String symbol);
}
