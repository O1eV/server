package com.ove.market.client;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;

import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class UpbitClient {

    private final RestClient restClient = RestClient.create("https://api.upbit.com");

    // 업비트 전체 종목 목록을 받아올 때 사용
    public List<UpbitMarketResponse> getMarkets() {
        UpbitMarketResponse[] markets = restClient.get()
                .uri("/v1/market/all")
                .retrieve()
                .body(UpbitMarketResponse[].class);
        return Arrays.asList(markets);
    }

    // 지정한 종목들의 현재가를 받아올 때 사용
    public List<UpbitTickerResponse> getTickers(List<String> symbols) {
        UpbitTickerResponse[] tickers = restClient.get()
                .uri("/v1/ticker?markets=" + String.join(",", symbols))
                .retrieve()
                .body(UpbitTickerResponse[].class);
        return Arrays.asList(tickers);
    }

    // 업비트 캔들을 최신순으로 최대 200개 받아올 때 사용
    public List<UpbitCandleResponse> getCandles(String candleType, String market, Instant to, int count) {
        String url = "/v1/candles/" + candleType + "?market=" + market + "&count=" + count;
        if (to != null) {
            url = url + "&to=" + to;
        }

        UpbitCandleResponse[] candles = restClient.get()
                .uri(url)
                .retrieve()
                .body(UpbitCandleResponse[].class);
        return Arrays.asList(candles);
    }
}
