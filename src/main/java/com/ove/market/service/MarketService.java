package com.ove.market.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.springframework.stereotype.Service;

import com.ove.global.exception.BusinessException;
import com.ove.global.exception.ErrorCode;
import com.ove.market.client.UpbitCandleResponse;
import com.ove.market.client.UpbitClient;
import com.ove.market.client.UpbitMarketResponse;
import com.ove.market.client.UpbitTickerResponse;
import com.ove.market.dto.CandleResponse;
import com.ove.market.dto.MarketResponse;
import com.ove.market.dto.MarketSearchResponse;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class MarketService {

    private final UpbitClient upbitClient;

    // 원화 종목에 현재가를 붙이고 필터에 맞는 종목만 골라낼 때 사용
    public List<MarketResponse> getMarkets(String assetClass, String marketState, String sign) {
        List<UpbitTickerResponse> tickers = upbitClient.getTickers();

        List<MarketResponse> result = new ArrayList<>();
        for (UpbitMarketResponse market : getKrwMarkets()) {
            UpbitTickerResponse ticker = findTicker(tickers, market.getMarket());
            if (ticker == null) {
                continue;
            }

            MarketResponse response = new MarketResponse(market, ticker);
            if (matches(assetClass, response.getAssetClass())
                    && matches(marketState, response.getMarketState())
                    && matches(sign, response.getSign())) {
                result.add(response);
            }
        }
        return result;
    }

    // 종목 코드·한글 이름·영어 이름에 검색어가 들어간 종목을 찾을 때 사용
    public List<MarketSearchResponse> searchMarkets(String q, int limit) {
        String keyword = q.toLowerCase();

        List<MarketSearchResponse> result = new ArrayList<>();
        for (UpbitMarketResponse market : getKrwMarkets()) {
            if (result.size() == limit) {
                break;
            }
            if (market.getMarket().toLowerCase().contains(keyword)
                    || market.getKoreanName().contains(q)
                    || market.getEnglishName().toLowerCase().contains(keyword)) {
                result.add(new MarketSearchResponse(market));
            }
        }
        return result;
    }

    // 업비트 캔들을 받아서 from 이후 캔들만 과거→최신 순으로 정리할 때 사용
    public List<CandleResponse> getCandles(String symbol, String interval, Instant from, Instant to, int limit) {
        String market = symbol.toUpperCase();
        if (!isKrwMarket(market)) {
            throw new BusinessException(ErrorCode.MARKET_NOT_FOUND);
        }

        int count = Math.min(limit, 200);
        List<UpbitCandleResponse> candles = upbitClient.getCandles(toUpbitCandleType(interval), market, to, count);

        List<CandleResponse> result = new ArrayList<>();
        for (UpbitCandleResponse candle : candles) {
            CandleResponse response = new CandleResponse(candle);
            if (from == null || !response.getTime().isBefore(from)) {
                result.add(response);
            }
        }
        Collections.reverse(result);
        return result;
    }

    // 업비트 전체 종목 중 원화(KRW-) 종목만 뽑을 때 사용
    private List<UpbitMarketResponse> getKrwMarkets() {
        List<UpbitMarketResponse> krwMarkets = new ArrayList<>();
        for (UpbitMarketResponse market : upbitClient.getMarkets()) {
            if (market.getMarket().startsWith("KRW-")) {
                krwMarkets.add(market);
            }
        }
        return krwMarkets;
    }

    // 원화 종목에 있는 코드인지 확인할 때 사용
    private boolean isKrwMarket(String symbol) {
        for (UpbitMarketResponse market : getKrwMarkets()) {
            if (market.getMarket().equals(symbol)) {
                return true;
            }
        }
        return false;
    }

    // 현재가 목록에서 종목 코드로 현재가를 찾을 때 사용
    private UpbitTickerResponse findTicker(List<UpbitTickerResponse> tickers, String symbol) {
        for (UpbitTickerResponse ticker : tickers) {
            if (ticker.getMarket().equals(symbol)) {
                return ticker;
            }
        }
        return null;
    }

    // 필터 값이 없거나(null) 값이 같으면 통과시킬 때 사용
    private boolean matches(String filter, String value) {
        return filter == null || filter.equals(value);
    }

    // interval(1m, 1h, 1d ...)을 업비트 캔들 주소로 바꿀 때 사용
    private String toUpbitCandleType(String interval) {
        switch (interval) {
            case "1m": return "minutes/1";
            case "3m": return "minutes/3";
            case "5m": return "minutes/5";
            case "10m": return "minutes/10";
            case "15m": return "minutes/15";
            case "30m": return "minutes/30";
            case "1h": return "minutes/60";
            case "4h": return "minutes/240";
            case "1d": return "days";
            case "1w": return "weeks";
            case "1M": return "months";
            default:
                throw new BusinessException(ErrorCode.INVALID_REQUEST,
                        "interval은 1m, 3m, 5m, 10m, 15m, 30m, 1h, 4h, 1d, 1w, 1M 중 하나여야 합니다.");
        }
    }
}
