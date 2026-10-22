package com.ove.market.service;

import java.math.BigDecimal;
import java.util.List;

import org.springframework.stereotype.Service;

import com.ove.global.exception.BusinessException;
import com.ove.global.exception.ErrorCode;
import com.ove.market.client.UpbitClient;
import com.ove.market.client.UpbitTickerResponse;

/**
 * Upbit 공개 REST API의 현재가를 모의 주문 체결가로 사용한다.
 * 업비트 호출 코드는 UpbitClient 한 곳에 두고(타임아웃·429 백오프·현재가 짧은 캐시 포함) 여기서는 재사용만 한다.
 * 거래소 요청이 실패하면 임의의 가격으로 대체하지 않고 주문을 거절한다.
 */
@Service
public class UpbitMarketPriceProvider implements MarketPriceProvider {

    private final UpbitClient upbitClient;

    public UpbitMarketPriceProvider(UpbitClient upbitClient) {
        this.upbitClient = upbitClient;
    }

    @Override
    public BigDecimal getLatestPrice(String symbol) {
        List<UpbitTickerResponse> tickers;
        try {
            tickers = upbitClient.getTickers(List.of(symbol));
        } catch (BusinessException e) {
            // 업비트 호출 실패(UPSTREAM_UNAVAILABLE)는 주문 쪽에서는 "현재 시세를 조회할 수 없음"으로 보여 준다.
            if (e.getErrorCode() == ErrorCode.UPSTREAM_UNAVAILABLE) {
                throw new BusinessException(ErrorCode.MARKET_PRICE_UNAVAILABLE);
            }
            throw e;
        }
        if (tickers == null || tickers.size() != 1 || tickers.get(0).getTradePrice() == null
                || tickers.get(0).getTradePrice().signum() <= 0) {
            throw new BusinessException(ErrorCode.MARKET_PRICE_UNAVAILABLE);
        }
        return tickers.get(0).getTradePrice();
    }
}
