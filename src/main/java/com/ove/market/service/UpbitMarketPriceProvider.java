package com.ove.market.service;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

import com.ove.global.exception.BusinessException;
import com.ove.global.exception.ErrorCode;
import com.ove.market.client.UpbitClient;
import com.ove.market.client.UpbitMarketResponse;
import com.ove.market.client.UpbitTickerResponse;

/**
 * Upbit 공개 REST API의 현재가를 모의 주문 체결가로 사용한다.
 * 업비트 호출 코드는 UpbitClient 한 곳에 두고(타임아웃·429 백오프·현재가 짧은 캐시 포함) 여기서는 재사용만 한다.
 * 주문 체결가는 시세를 못 구하면 임의 가격으로 대체하지 않고 거절하지만, 포트폴리오 평가는 일부 종목이 빠져도 계속 조회한다.
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
        Map<String, BigDecimal> prices;
        try {
            prices = fetchPrices(List.of(symbol));
        } catch (BusinessException e) {
            // 업비트 호출 실패(UPSTREAM_UNAVAILABLE)는 모의 거래에서 "현재 시세를 조회할 수 없음"으로 보여 준다.
            if (e.getErrorCode() == ErrorCode.UPSTREAM_UNAVAILABLE) {
                throw new BusinessException(ErrorCode.MARKET_PRICE_UNAVAILABLE);
            }
            throw e;
        }

        BigDecimal price = prices.get(symbol);
        if (price == null) {
            throw new BusinessException(ErrorCode.MARKET_PRICE_UNAVAILABLE);
        }
        return price;
    }

    /**
     * 상장폐지·거래 중단 종목이 하나 있어도 포트폴리오 전체가 실패하지 않도록,
     * 거래소 종목 목록에 있는 종목만 현재가를 요청하고 실패한 종목은 결과에서 뺀다.
     */
    @Override
    public Map<String, BigDecimal> getPortfolioPrices(List<String> symbols) {
        List<String> distinctSymbols = symbols.stream().filter(Objects::nonNull).distinct().toList();
        if (distinctSymbols.isEmpty()) {
            return Map.of();
        }

        List<String> requestSymbols;
        try {
            List<UpbitMarketResponse> markets = upbitClient.getMarkets();
            if (markets == null || markets.isEmpty()) {
                return Map.of();
            }
            Set<String> validMarkets = markets.stream()
                    .map(UpbitMarketResponse::getMarket)
                    .filter(Objects::nonNull)
                    .collect(Collectors.toSet());
            requestSymbols = distinctSymbols.stream().filter(validMarkets::contains).toList();
        } catch (BusinessException e) {
            // 종목 목록을 못 구하면 이번 포트폴리오는 평균 매수가로 평가하게 비운다.
            return Map.of();
        }

        if (requestSymbols.isEmpty()) {
            return Map.of();
        }
        try {
            return Map.copyOf(fetchPrices(requestSymbols));
        } catch (BusinessException e) {
            return Map.of();
        }
    }

    // 같은 종목이 두 번 오면 어느 가격이 맞는지 알 수 없으므로 그 종목은 결과에서 뺀다.
    private Map<String, BigDecimal> fetchPrices(List<String> symbols) {
        List<UpbitTickerResponse> tickers = upbitClient.getTickers(symbols);
        Set<String> requestedSymbols = Set.copyOf(symbols);
        Set<String> duplicatedSymbols = new HashSet<>();
        Map<String, BigDecimal> prices = new HashMap<>();
        if (tickers == null) {
            return prices;
        }

        for (UpbitTickerResponse ticker : tickers) {
            if (ticker == null || !requestedSymbols.contains(ticker.getMarket())
                    || ticker.getTradePrice() == null || ticker.getTradePrice().signum() <= 0
                    || duplicatedSymbols.contains(ticker.getMarket())) {
                continue;
            }
            if (prices.putIfAbsent(ticker.getMarket(), ticker.getTradePrice()) != null) {
                prices.remove(ticker.getMarket());
                duplicatedSymbols.add(ticker.getMarket());
            }
        }
        return prices;
    }
}
