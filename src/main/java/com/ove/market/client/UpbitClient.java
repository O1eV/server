package com.ove.market.client;

import java.net.http.HttpClient;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import com.ove.global.exception.BusinessException;
import com.ove.global.exception.ErrorCode;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
public class UpbitClient {

    private final RestClient restClient;
    private final Duration marketsCacheTtl;

    // 종목 목록 캐시. 목록은 거의 안 바뀌는데 요청마다 업비트를 부르면 호출이 늘어나서 짧게 들고 있는다.
    private List<UpbitMarketResponse> cachedMarkets;
    private long cachedMarketsAtNanos;

    // 설정값은 application.yaml의 upbit.* 로 바꿀 수 있고, 없으면 기본값을 쓴다.
    // 업비트가 느려져도 요청 스레드가 오래 묶이지 않도록 연결·읽기 타임아웃을 짧게 둔다.
    public UpbitClient(
            @Value("${upbit.base-url:https://api.upbit.com}") String baseUrl,
            @Value("${upbit.connect-timeout:500ms}") Duration connectTimeout,
            @Value("${upbit.read-timeout:1s}") Duration readTimeout,
            @Value("${upbit.markets-cache-ttl:5m}") Duration marketsCacheTtl) {
        HttpClient httpClient = HttpClient.newBuilder().connectTimeout(connectTimeout).build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(readTimeout);

        this.restClient = RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(requestFactory)
                .build();
        this.marketsCacheTtl = marketsCacheTtl;
    }

    // 업비트 전체 종목 목록을 받아올 때 사용 (TTL 동안은 캐시된 값을 돌려준다)
    public synchronized List<UpbitMarketResponse> getMarkets() {
        long now = System.nanoTime();
        if (cachedMarkets != null && now - cachedMarketsAtNanos < marketsCacheTtl.toNanos()) {
            return cachedMarkets;
        }

        // 호출이 실패하면 예외가 그대로 올라가므로 실패 결과는 캐시되지 않는다.
        cachedMarkets = get("/v1/market/all", UpbitMarketResponse[].class);
        cachedMarketsAtNanos = now;
        return cachedMarkets;
    }

    // 지정한 종목들의 현재가를 받아올 때 사용
    public List<UpbitTickerResponse> getTickers(List<String> symbols) {
        return get("/v1/ticker?markets=" + String.join(",", symbols), UpbitTickerResponse[].class);
    }

    // 업비트 캔들을 최신순으로 최대 200개 받아올 때 사용
    public List<UpbitCandleResponse> getCandles(String candleType, String market, Instant to, int count) {
        String url = "/v1/candles/" + candleType + "?market=" + market + "&count=" + count;
        if (to != null) {
            url = url + "&to=" + to;
        }
        return get(url, UpbitCandleResponse[].class);
    }

    // 업비트 호출 실패(429·5xx·연결 실패·타임아웃·빈 응답)를 모두 UPSTREAM_UNAVAILABLE(502)로 바꿀 때 사용
    private <T> List<T> get(String uri, Class<T[]> responseType) {
        T[] body;
        try {
            body = restClient.get().uri(uri).retrieve().body(responseType);
        } catch (RestClientResponseException e) {
            log.warn("Upbit 응답 오류: status={}, uri={}", e.getStatusCode().value(), uri);
            throw new BusinessException(ErrorCode.UPSTREAM_UNAVAILABLE);
        } catch (RestClientException e) {
            log.warn("Upbit 호출 실패: uri={}, cause={}", uri, e.toString());
            throw new BusinessException(ErrorCode.UPSTREAM_UNAVAILABLE);
        }

        if (body == null) {
            log.warn("Upbit 응답 본문이 비어 있음: uri={}", uri);
            throw new BusinessException(ErrorCode.UPSTREAM_UNAVAILABLE);
        }
        return Arrays.asList(body);
    }
}
