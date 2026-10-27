package com.ove.market.client;

import java.net.http.HttpClient;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import com.ove.global.exception.BusinessException;
import com.ove.global.exception.ErrorCode;

import lombok.extern.slf4j.Slf4j;

/**
 * 업비트 시세 REST 클라이언트.
 * 업비트는 시세 조회에 IP 단위 초당 호출 한도가 있고(그룹별 초당 10회), 429를 계속 받으면 418로 차단한다.
 * 그래서 현재가는 짧게 캐시하고, 429를 받으면 잠깐 쉬었다가 한 번만 다시 시도한다.
 */
@Slf4j
@Component
public class UpbitClient {

    private static final int TOO_MANY_REQUESTS = 429;
    // 현재가 캐시 키는 사용자 입력(모의 주문 종목)과 사용자별 보유 종목 조합이라 계속 늘 수 있다.
    // 오래된 항목을 지워 메모리가 무한히 커지지 않게 상한을 둔다.
    private static final int MAX_TICKER_CACHE_ENTRIES = 200;

    private final RestClient restClient;
    private final Duration marketsCacheTtl;
    private final Duration tickerCacheTtl;
    private final Duration rateLimitBackoff;

    // 종목 목록 캐시. 목록은 거의 안 바뀌는데 요청마다 업비트를 부르면 호출이 늘어나서 짧게 들고 있는다.
    private List<UpbitMarketResponse> cachedMarkets;
    private long cachedMarketsAtNanos;

    // 현재가 캐시. 요청한 종목 묶음("KRW-BTC,KRW-ETH,...")별로 1~2초만 들고 있어서,
    // 동시에 여러 요청이 와도 업비트에는 TTL마다 한 번만 간다.
    // 키에는 사용자 입력(모의 주문)과 사용자별 보유 종목 조합이 들어올 수 있어, 접근 순서 LRU로 상한을 둔다.
    private final Object tickersLock = new Object();
    private final Map<String, CachedTickers> cachedTickers = new LinkedHashMap<>(16, 0.75f, true);

    // 설정값은 application.yaml의 upbit.* 로 바꿀 수 있고, 없으면 기본값을 쓴다.
    // 업비트가 느려져도 요청 스레드가 오래 묶이지 않도록 연결·읽기 타임아웃을 짧게 둔다.
    public UpbitClient(
            @Value("${upbit.base-url:https://api.upbit.com}") String baseUrl,
            @Value("${upbit.connect-timeout:500ms}") Duration connectTimeout,
            @Value("${upbit.read-timeout:1s}") Duration readTimeout,
            @Value("${upbit.markets-cache-ttl:5m}") Duration marketsCacheTtl,
            @Value("${upbit.ticker-cache-ttl:1500ms}") Duration tickerCacheTtl,
            @Value("${upbit.rate-limit-backoff:300ms}") Duration rateLimitBackoff) {
        HttpClient httpClient = HttpClient.newBuilder().connectTimeout(connectTimeout).build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(readTimeout);

        this.restClient = RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(requestFactory)
                .build();
        this.marketsCacheTtl = marketsCacheTtl;
        this.tickerCacheTtl = tickerCacheTtl;
        this.rateLimitBackoff = rateLimitBackoff;
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

    // 지정한 종목들의 현재가를 받아올 때 사용 (TTL 동안은 캐시된 값을 돌려준다)
    public List<UpbitTickerResponse> getTickers(List<String> symbols) {
        String key = String.join(",", symbols);

        // 락을 잡은 채로 업비트를 부르므로, 캐시가 만료된 순간 몰려온 요청들은 첫 요청의 결과를 같이 쓴다.
        synchronized (tickersLock) {
            long now = System.nanoTime();
            CachedTickers cached = cachedTickers.get(key);
            if (cached != null && now - cached.fetchedAtNanos() < tickerCacheTtl.toNanos()) {
                return cached.tickers();
            }

            // 호출이 실패하면 예외가 그대로 올라가므로 실패 결과는 캐시되지 않는다.
            List<UpbitTickerResponse> fresh = get("/v1/ticker?markets=" + key, UpbitTickerResponse[].class);
            cachedTickers.put(key, new CachedTickers(fresh, now));
            evictOldTickerCache(now);
            return fresh;
        }
    }

    // 만료된 키를 먼저 지우고, 그래도 상한을 넘으면 가장 오래 쓰지 않은 키부터 지운다.
    private void evictOldTickerCache(long now) {
        cachedTickers.entrySet().removeIf(entry ->
                now - entry.getValue().fetchedAtNanos() >= tickerCacheTtl.toNanos());
        while (cachedTickers.size() > MAX_TICKER_CACHE_ENTRIES) {
            Iterator<Map.Entry<String, CachedTickers>> iterator = cachedTickers.entrySet().iterator();
            iterator.next();
            iterator.remove();
        }
    }

    // 업비트 캔들을 최신순으로 최대 200개 받아올 때 사용 (캔들은 조건이 다양해서 캐시하지 않는다)
    public List<UpbitCandleResponse> getCandles(String candleType, String market, Instant to, int count) {
        String url = "/v1/candles/" + candleType + "?market=" + market + "&count=" + count;
        if (to != null) {
            url = url + "&to=" + to;
        }
        return get(url, UpbitCandleResponse[].class);
    }

    // 업비트 호출 실패(429·5xx·연결 실패·타임아웃·빈 응답)를 모두 UPSTREAM_UNAVAILABLE(502)로 바꿀 때 사용
    // 429(호출 한도 초과)만 잠깐 쉬었다가 한 번 더 시도한다. 418(차단)이나 5xx는 다시 불러도 소용없어서 재시도하지 않는다.
    private <T> List<T> get(String uri, Class<T[]> responseType) {
        try {
            return request(uri, responseType);
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().value() != TOO_MANY_REQUESTS) {
                log.warn("Upbit 응답 오류: status={}, uri={}", e.getStatusCode().value(), uri);
                throw new BusinessException(ErrorCode.UPSTREAM_UNAVAILABLE);
            }
            log.warn("Upbit 호출 한도 초과(429): {}ms 뒤 한 번 재시도, uri={}", rateLimitBackoff.toMillis(), uri);
        } catch (RestClientException e) {
            log.warn("Upbit 호출 실패: uri={}, cause={}", uri, e.toString());
            throw new BusinessException(ErrorCode.UPSTREAM_UNAVAILABLE);
        }

        sleepForBackoff();
        try {
            return request(uri, responseType);
        } catch (RestClientResponseException e) {
            log.warn("Upbit 재시도 실패: status={}, uri={}", e.getStatusCode().value(), uri);
            throw new BusinessException(ErrorCode.UPSTREAM_UNAVAILABLE);
        } catch (RestClientException e) {
            log.warn("Upbit 재시도 실패: uri={}, cause={}", uri, e.toString());
            throw new BusinessException(ErrorCode.UPSTREAM_UNAVAILABLE);
        }
    }

    // 업비트를 한 번 호출한다. 본문이 비어 있으면 NPE 대신 UPSTREAM_UNAVAILABLE을 던진다.
    private <T> List<T> request(String uri, Class<T[]> responseType) {
        T[] body = restClient.get().uri(uri).retrieve().body(responseType);
        if (body == null) {
            log.warn("Upbit 응답 본문이 비어 있음: uri={}", uri);
            throw new BusinessException(ErrorCode.UPSTREAM_UNAVAILABLE);
        }
        return Arrays.asList(body);
    }

    private void sleepForBackoff() {
        try {
            Thread.sleep(rateLimitBackoff.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BusinessException(ErrorCode.UPSTREAM_UNAVAILABLE);
        }
    }

    private record CachedTickers(List<UpbitTickerResponse> tickers, long fetchedAtNanos) {
    }
}
