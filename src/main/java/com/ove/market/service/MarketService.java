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
import com.ove.market.dto.CandleListResponse;
import com.ove.market.dto.CandleResponse;
import com.ove.market.dto.MarketListResponse;
import com.ove.market.dto.MarketResponse;
import com.ove.market.dto.MarketSearchListResponse;
import com.ove.market.dto.MarketSearchResponse;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class MarketService {

    private static final List<String> SUPPORTED_SYMBOLS = List.of("KRW-BTC", "KRW-ETH", "KRW-XRP");
    private static final List<String> ASSET_CLASSES = List.of("COIN", "KR_STOCK", "US_STOCK");
    private static final List<String> MARKET_STATES = List.of("UP", "DOWN", "SIDEWAYS", "VOLATILE");
    private static final List<String> SIGNALS = List.of("BUY", "HOLD", "SELL");
    private static final int UPBIT_MAX_COUNT = 200;
    private static final int MAX_CANDLES = 500;
    private static final int MAX_PAGE_SIZE = 100;
    private static final int MAX_SEARCH_LIMIT = 50;

    private final UpbitClient upbitClient;

    // 자산군·시장 상태·신호 필터에 맞는 종목을 페이지 단위로 조회할 때 사용
    public MarketListResponse getMarkets(String assetClass, String marketState, String signal, int page, int size) {
        checkFilter(assetClass, ASSET_CLASSES);
        checkFilter(marketState, MARKET_STATES);
        checkFilter(signal, SIGNALS);
        if (page < 0 || size < 1 || size > MAX_PAGE_SIZE) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST,
                    "page는 0 이상, size는 1~" + MAX_PAGE_SIZE + " 사이여야 합니다.");
        }

        List<UpbitTickerResponse> tickers = upbitClient.getTickers(SUPPORTED_SYMBOLS);

        List<MarketResponse> result = new ArrayList<>();
        for (UpbitMarketResponse market : getSupportedMarkets()) {
            UpbitTickerResponse ticker = findTicker(tickers, market.getMarket());
            if (ticker == null) {
                continue;
            }

            MarketResponse response = new MarketResponse(market, ticker);
            if (matches(assetClass, response.getAssetClass())
                    && matches(marketState, response.getMarketState())
                    && matches(signal, response.getSignal())) {
                result.add(response);
            }
        }

        // page가 아주 크면 page * size가 int 범위를 넘어 음수가 되므로 long으로 계산한다.
        int start = (int) Math.min((long) page * size, result.size());
        int end = Math.min(start + size, result.size());
        return new MarketListResponse(result.subList(start, end), page, size, result.size());
    }

    // 한글 이름·영어 이름·심볼에 검색어가 들어간 종목을 찾을 때 사용
    public MarketSearchListResponse searchMarkets(String q, int limit) {
        if (q == null || q.isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "검색어를 입력해 주세요.");
        }
        if (limit < 1 || limit > MAX_SEARCH_LIMIT) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "limit은 1~" + MAX_SEARCH_LIMIT + " 사이여야 합니다.");
        }
        String keyword = q.trim().toLowerCase();

        List<MarketSearchResponse> result = new ArrayList<>();
        for (UpbitMarketResponse market : getSupportedMarkets()) {
            if (result.size() >= limit) {
                break;
            }
            if (market.getMarket().toLowerCase().contains(keyword)
                    || market.getKoreanName().contains(keyword)
                    || market.getEnglishName().toLowerCase().contains(keyword)) {
                result.add(new MarketSearchResponse(market));
            }
        }
        return new MarketSearchListResponse(result);
    }

    // 종목의 캔들을 시간 오름차순으로 조회할 때 사용
    public CandleListResponse getCandles(String symbol, String interval, Instant from, Instant to, int limit) {
        String market = symbol.toUpperCase();
        if (!SUPPORTED_SYMBOLS.contains(market)) {
            throw new BusinessException(ErrorCode.MARKET_NOT_FOUND);
        }
        // 업비트를 부르기 전에 요청 값을 먼저 모두 검증한다.
        String candleType = toUpbitCandleType(interval);
        if (limit < 1 || limit > MAX_CANDLES) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "limit은 1~500 사이여야 합니다.");
        }
        if (from != null && to != null && from.isAfter(to)) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "from은 to보다 늦을 수 없습니다.");
        }

        List<CandleResponse> candles = new ArrayList<>();
        for (UpbitCandleResponse upbitCandle : fetchCandles(candleType, market, from, to, limit)) {
            candles.add(new CandleResponse(upbitCandle));
        }
        Collections.reverse(candles);
        return new CandleListResponse(market, interval, candles);
    }

    // 업비트는 한 번에 200개까지만 줘서, limit만큼 찰 때까지 나눠서 받아올 때 사용
    // 캔들은 최신순으로 오므로 from보다 오래된 캔들을 만나면 더 받지 않고 멈춘다.
    private List<UpbitCandleResponse> fetchCandles(String candleType, String market, Instant from, Instant to,
                                                   int limit) {
        List<UpbitCandleResponse> result = new ArrayList<>();
        Instant cursor = to;
        while (result.size() < limit) {
            int count = Math.min(UPBIT_MAX_COUNT, limit - result.size());
            List<UpbitCandleResponse> batch = upbitClient.getCandles(candleType, market, cursor, count);
            for (UpbitCandleResponse candle : batch) {
                if (from != null && candle.toInstant().isBefore(from)) {
                    return result;
                }
                result.add(candle);
            }
            if (batch.size() < count) {
                break;
            }
            // 업비트의 to는 그 시각을 포함하지 않아서, 마지막 캔들 시각을 넘기면 다음 묶음과 겹치지 않는다.
            cursor = batch.get(batch.size() - 1).toInstant();
        }
        return result;
    }

    // 제공하는 종목인지 확인할 때 사용
    public boolean isSupported(String symbol) {
        return SUPPORTED_SYMBOLS.contains(symbol);
    }

    // 지정한 종목들의 이름·현재가를 한 번에 가져올 때 사용
    public List<MarketResponse> getMarketsBySymbols(List<String> symbols) {
        if (symbols.isEmpty()) {
            return List.of();
        }
        List<UpbitTickerResponse> tickers = upbitClient.getTickers(symbols);

        List<MarketResponse> result = new ArrayList<>();
        for (UpbitMarketResponse market : getSupportedMarkets()) {
            UpbitTickerResponse ticker = findTicker(tickers, market.getMarket());
            if (symbols.contains(market.getMarket()) && ticker != null) {
                result.add(new MarketResponse(market, ticker));
            }
        }
        return result;
    }

    // 업비트 전체 종목 중 MVP에서 제공하는 종목만 뽑을 때 사용
    private List<UpbitMarketResponse> getSupportedMarkets() {
        List<UpbitMarketResponse> supported = new ArrayList<>();
        for (UpbitMarketResponse market : upbitClient.getMarkets()) {
            if (SUPPORTED_SYMBOLS.contains(market.getMarket())) {
                supported.add(market);
            }
        }
        return supported;
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

    // 필터 값이 정해진 값 중 하나인지 확인할 때 사용
    private void checkFilter(String value, List<String> allowed) {
        if (value != null && !allowed.contains(value)) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "유효하지 않은 필터 값입니다.");
        }
    }

    // 필터 값이 없거나(null) 값이 같으면 통과시킬 때 사용
    private boolean matches(String filter, String value) {
        return filter == null || filter.equals(value);
    }

    // interval(1m, 5m, 15m, 1h, 1d)을 업비트 캔들 주소로 바꿀 때 사용
    private String toUpbitCandleType(String interval) {
        switch (interval) {
            case "1m": return "minutes/1";
            case "5m": return "minutes/5";
            case "15m": return "minutes/15";
            case "1h": return "minutes/60";
            case "1d": return "days";
            default:
                throw new BusinessException(ErrorCode.INVALID_REQUEST,
                        "interval은 1m, 5m, 15m, 1h, 1d 중 하나여야 합니다.");
        }
    }
}
