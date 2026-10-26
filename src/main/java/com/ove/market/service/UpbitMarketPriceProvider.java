package com.ove.market.service;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import com.ove.global.exception.BusinessException;
import com.ove.global.exception.ErrorCode;
import com.ove.market.client.UpbitClient;
import com.ove.market.client.UpbitMarketResponse;
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

    /**
     * 종목 목록 캐시(기본 5분)로 거래 가능한 종목인지 먼저 확인한다.
     * 형식만 맞는 없는 종목을 시세 조회까지 보내면 업비트가 4xx를 주고, 그 응답이 503처럼 보이기 때문이다.
     */
    @Override
    public void validateMarket(String symbol) {
        List<UpbitMarketResponse> markets = upbitClient.getMarkets();
        boolean exists = markets.stream().anyMatch(market -> market.getMarket().equals(symbol));
        if (!exists) {
            throw new BusinessException(ErrorCode.MARKET_NOT_FOUND);
        }
    }

    @Override
    public BigDecimal getLatestPrice(String symbol) {
        return getLatestPrices(List.of(symbol)).get(symbol);
    }

    @Override
    public Map<String, BigDecimal> getLatestPrices(List<String> symbols) {
        if (symbols.isEmpty()) {
            return Map.of();
        }

        List<UpbitTickerResponse> tickers;
        try {
            tickers = upbitClient.getTickers(symbols);
        } catch (BusinessException e) {
            // 업비트 호출 실패(UPSTREAM_UNAVAILABLE)는 모의 거래에서 "현재 시세를 조회할 수 없음"으로 보여 준다.
            if (e.getErrorCode() == ErrorCode.UPSTREAM_UNAVAILABLE) {
                throw new BusinessException(ErrorCode.MARKET_PRICE_UNAVAILABLE);
            }
            throw e;
        }

        Map<String, BigDecimal> prices = new HashMap<>();
        if (tickers != null) {
            for (UpbitTickerResponse ticker : tickers) {
                if (ticker != null && symbols.contains(ticker.getMarket()) && ticker.getTradePrice() != null
                        && ticker.getTradePrice().signum() > 0) {
                    // 같은 종목이 두 번 오면 어느 가격이 맞는지 알 수 없으므로 임의로 고르지 않는다.
                    if (prices.putIfAbsent(ticker.getMarket(), ticker.getTradePrice()) != null) {
                        throw new BusinessException(ErrorCode.MARKET_PRICE_UNAVAILABLE);
                    }
                }
            }
        }
        if (prices.size() != symbols.stream().distinct().count()) {
            throw new BusinessException(ErrorCode.MARKET_PRICE_UNAVAILABLE);
        }
        return Map.copyOf(prices);
    }
}
