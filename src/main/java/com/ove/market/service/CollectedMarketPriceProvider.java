package com.ove.market.service;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import org.springframework.stereotype.Service;
import com.ove.global.exception.BusinessException;
import com.ove.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class CollectedMarketPriceProvider implements MarketPriceProvider {
    private final CollectedPriceService prices;
    @Override public void validateMarket(String symbol) { MarketService.requireSupportedSymbol(symbol); }
    @Override public BigDecimal getLatestPrice(String symbol) {
        validateMarket(symbol);
        try {
            var quote = prices.getPrice(symbol);
            if (quote.stale()) throw new BusinessException(ErrorCode.MARKET_PRICE_UNAVAILABLE);
            return quote.price();
        } catch (BusinessException error) {
            if (error.getErrorCode() == ErrorCode.COLLECTOR_UNAVAILABLE) throw new BusinessException(ErrorCode.MARKET_PRICE_UNAVAILABLE);
            throw error;
        }
    }
    @Override public Map<String, BigDecimal> getLatestPrices(List<String> symbols) {
        Map<String, BigDecimal> result = new HashMap<>();
        for (String symbol : symbols.stream().distinct().toList()) result.put(symbol, getLatestPrice(symbol));
        return Map.copyOf(result);
    }
}
