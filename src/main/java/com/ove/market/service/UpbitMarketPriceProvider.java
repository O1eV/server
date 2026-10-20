package com.ove.market.service;

import java.math.BigDecimal;

import com.fasterxml.jackson.annotation.JsonProperty;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import com.ove.global.exception.BusinessException;
import com.ove.global.exception.ErrorCode;

/**
 * Upbit 공개 REST API의 현재가를 모의 주문 체결가로 사용한다.
 * 거래소 요청이 실패하면 임의의 가격으로 대체하지 않고 주문을 거절한다.
 */
@Service
public class UpbitMarketPriceProvider implements MarketPriceProvider {

    private final RestClient restClient;

    public UpbitMarketPriceProvider(RestClient.Builder restClientBuilder,
                                    @Value("${paper-trading.upbit.base-url}") String baseUrl) {
        this.restClient = restClientBuilder.baseUrl(baseUrl).build();
    }

    @Override
    public BigDecimal getLatestPrice(String symbol) {
        try {
            UpbitTicker[] tickers = restClient.get()
                    .uri(uriBuilder -> uriBuilder.path("/v1/ticker")
                            .queryParam("markets", symbol)
                            .build())
                    .retrieve()
                    .body(UpbitTicker[].class);
            if (tickers == null || tickers.length != 1 || tickers[0].tradePrice() == null
                    || tickers[0].tradePrice().signum() <= 0) {
                throw new BusinessException(ErrorCode.MARKET_PRICE_UNAVAILABLE);
            }
            return tickers[0].tradePrice();
        } catch (RestClientException e) {
            throw new BusinessException(ErrorCode.MARKET_PRICE_UNAVAILABLE);
        }
    }

    // Upbit의 snake_case 응답 필드를 명시적으로 매핑한다.
    private record UpbitTicker(@JsonProperty("trade_price") BigDecimal tradePrice) {
    }
}
