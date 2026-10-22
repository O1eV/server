package com.ove.market.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.ove.global.exception.BusinessException;
import com.ove.global.exception.ErrorCode;
import com.sun.net.httpserver.HttpServer;

/**
 * 진짜 HTTP 서버(JDK 내장)를 띄워 업비트 응답 실패·타임아웃·캐시 동작을 확인한다.
 */
class UpbitClientTest {

    private static final String MARKETS_JSON =
            "[{\"market\":\"KRW-BTC\",\"korean_name\":\"비트코인\",\"english_name\":\"Bitcoin\"}]";

    private HttpServer server;
    private final AtomicInteger requestCount = new AtomicInteger();

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("정상 응답이면 종목 목록을 파싱한다")
    void getMarkets() throws IOException {
        startServer(200, MARKETS_JSON, 0);

        List<UpbitMarketResponse> markets = client(Duration.ofSeconds(1), Duration.ZERO).getMarkets();

        assertThat(markets).hasSize(1);
        assertThat(markets.get(0).getMarket()).isEqualTo("KRW-BTC");
        assertThat(markets.get(0).getKoreanName()).isEqualTo("비트코인");
    }

    @Test
    @DisplayName("업비트가 429(호출 제한)를 주면 UPSTREAM_UNAVAILABLE")
    void rateLimited() throws IOException {
        startServer(429, "{\"error\":{\"name\":\"too_many_requests\"}}", 0);

        assertUpstreamUnavailable(() -> client(Duration.ofSeconds(1), Duration.ZERO).getMarkets());
    }

    @Test
    @DisplayName("업비트가 5xx를 주면 UPSTREAM_UNAVAILABLE")
    void serverError() throws IOException {
        startServer(503, "unavailable", 0);

        assertUpstreamUnavailable(() -> client(Duration.ofSeconds(1), Duration.ZERO)
                .getTickers(List.of("KRW-BTC")));

        // 5xx는 다시 불러도 소용없어서 재시도하지 않는다.
        assertThat(requestCount.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("응답이 읽기 타임아웃을 넘기면 UPSTREAM_UNAVAILABLE")
    void readTimeout() throws IOException {
        startServer(200, MARKETS_JSON, 1500);
        UpbitClient client = new UpbitClient(baseUrl(), Duration.ofMillis(500), Duration.ofMillis(200),
                Duration.ZERO, Duration.ZERO, Duration.ZERO);

        long startedAt = System.nanoTime();
        assertUpstreamUnavailable(client::getMarkets);

        // 서버가 1.5초 뒤에 응답하는데도 타임아웃(0.2초) 근처에서 끊겨야 한다.
        assertThat(Duration.ofNanos(System.nanoTime() - startedAt)).isLessThan(Duration.ofSeconds(1));
    }

    @Test
    @DisplayName("서버에 연결할 수 없으면 UPSTREAM_UNAVAILABLE")
    void connectionRefused() throws IOException {
        startServer(200, MARKETS_JSON, 0);
        String url = baseUrl();
        server.stop(0);
        server = null;

        assertUpstreamUnavailable(() -> new UpbitClient(url, Duration.ofMillis(500), Duration.ofSeconds(1),
                Duration.ZERO, Duration.ZERO, Duration.ZERO).getCandles("minutes/1", "KRW-BTC", null, 10));
    }

    @Test
    @DisplayName("응답 본문이 비어 있으면(null) NPE 대신 UPSTREAM_UNAVAILABLE")
    void nullBody() throws IOException {
        startServer(200, "null", 0);

        assertUpstreamUnavailable(() -> client(Duration.ofSeconds(1), Duration.ZERO).getMarkets());
    }

    @Test
    @DisplayName("JSON이 아닌 응답이면 UPSTREAM_UNAVAILABLE")
    void brokenBody() throws IOException {
        startServer(200, "<html>oops</html>", 0);

        assertUpstreamUnavailable(() -> client(Duration.ofSeconds(1), Duration.ZERO).getMarkets());
    }

    @Test
    @DisplayName("종목 목록은 TTL 동안 캐시되어 업비트를 한 번만 부른다")
    void marketsCache() throws IOException {
        startServer(200, MARKETS_JSON, 0);
        UpbitClient client = client(Duration.ofSeconds(1), Duration.ofMinutes(5));

        client.getMarkets();
        client.getMarkets();
        client.getMarkets();

        assertThat(requestCount.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("TTL이 0이면 캐시하지 않고 매번 업비트를 부른다")
    void marketsCacheDisabled() throws IOException {
        startServer(200, MARKETS_JSON, 0);
        UpbitClient client = client(Duration.ofSeconds(1), Duration.ZERO);

        client.getMarkets();
        client.getMarkets();

        assertThat(requestCount.get()).isEqualTo(2);
    }

    @Test
    @DisplayName("실패한 호출은 캐시하지 않아서 다음 호출이 다시 업비트를 부른다")
    void failureIsNotCached() throws IOException {
        startServer(500, "error", 0);
        UpbitClient client = client(Duration.ofSeconds(1), Duration.ofMinutes(5));

        assertUpstreamUnavailable(client::getMarkets);
        assertUpstreamUnavailable(client::getMarkets);

        assertThat(requestCount.get()).isEqualTo(2);
    }

    @Test
    @DisplayName("현재가는 TTL 동안 캐시되어 업비트를 한 번만 부른다")
    void tickerCache() throws IOException {
        startServer(200, "[]", 0);
        UpbitClient client = client(Duration.ofSeconds(1), Duration.ZERO, Duration.ofMinutes(5), Duration.ZERO);

        client.getTickers(List.of("KRW-BTC", "KRW-ETH"));
        client.getTickers(List.of("KRW-BTC", "KRW-ETH"));
        client.getTickers(List.of("KRW-BTC", "KRW-ETH"));

        assertThat(requestCount.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("종목 묶음이 다르면 현재가 캐시도 따로 쓴다")
    void tickerCachePerSymbolSet() throws IOException {
        startServer(200, "[]", 0);
        UpbitClient client = client(Duration.ofSeconds(1), Duration.ZERO, Duration.ofMinutes(5), Duration.ZERO);

        client.getTickers(List.of("KRW-BTC"));
        client.getTickers(List.of("KRW-BTC", "KRW-ETH"));
        client.getTickers(List.of("KRW-BTC"));
        client.getTickers(List.of("KRW-BTC", "KRW-ETH"));

        assertThat(requestCount.get()).isEqualTo(2);
    }

    @Test
    @DisplayName("현재가 캐시는 TTL이 지나면 다시 업비트를 부른다")
    void tickerCacheExpires() throws Exception {
        startServer(200, "[]", 0);
        UpbitClient client = client(Duration.ofSeconds(1), Duration.ZERO, Duration.ofMillis(100), Duration.ZERO);

        client.getTickers(List.of("KRW-BTC"));
        Thread.sleep(250);
        client.getTickers(List.of("KRW-BTC"));

        assertThat(requestCount.get()).isEqualTo(2);
    }

    @Test
    @DisplayName("현재가 TTL이 0이면 캐시하지 않고 매번 업비트를 부른다")
    void tickerCacheDisabled() throws IOException {
        startServer(200, "[]", 0);
        UpbitClient client = client(Duration.ofSeconds(1), Duration.ZERO, Duration.ZERO, Duration.ZERO);

        client.getTickers(List.of("KRW-BTC"));
        client.getTickers(List.of("KRW-BTC"));

        assertThat(requestCount.get()).isEqualTo(2);
    }

    @Test
    @DisplayName("현재가 호출이 실패하면 캐시하지 않아서 다음 호출이 다시 업비트를 부른다")
    void tickerFailureIsNotCached() throws IOException {
        startServer(500, "error", 0);
        UpbitClient client = client(Duration.ofSeconds(1), Duration.ZERO, Duration.ofMinutes(5), Duration.ZERO);

        assertUpstreamUnavailable(() -> client.getTickers(List.of("KRW-BTC")));
        assertUpstreamUnavailable(() -> client.getTickers(List.of("KRW-BTC")));

        assertThat(requestCount.get()).isEqualTo(2);
    }

    @Test
    @DisplayName("동시에 몰려온 현재가 요청도 업비트는 한 번만 부른다")
    void tickerCacheUnderConcurrency() throws Exception {
        startServer(200, "[]", 100);
        UpbitClient client = client(Duration.ofSeconds(2), Duration.ZERO, Duration.ofMinutes(5), Duration.ZERO);
        int threads = 20;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    go.await();
                    return client.getTickers(List.of("KRW-BTC"));
                }));
            }
            ready.await();
            go.countDown();
            for (Future<?> future : futures) {
                future.get(5, TimeUnit.SECONDS);
            }
        } finally {
            executor.shutdownNow();
        }

        assertThat(requestCount.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("캔들은 캐시하지 않는다")
    void candlesAreNotCached() throws IOException {
        startServer(200, "[]", 0);
        UpbitClient client = client(Duration.ofSeconds(1), Duration.ofMinutes(5), Duration.ofMinutes(5),
                Duration.ZERO);

        client.getCandles("minutes/1", "KRW-BTC", null, 10);
        client.getCandles("minutes/1", "KRW-BTC", null, 10);

        assertThat(requestCount.get()).isEqualTo(2);
    }

    @Test
    @DisplayName("429를 받으면 백오프 후 한 번 재시도하고, 성공하면 결과를 돌려준다")
    void retryOnceOnTooManyRequests() throws IOException {
        startScriptedServer(new int[] {429, 200}, "[]");
        UpbitClient client = client(Duration.ofSeconds(1), Duration.ZERO, Duration.ZERO, Duration.ofMillis(150));

        long startedAt = System.nanoTime();
        List<UpbitTickerResponse> tickers = client.getTickers(List.of("KRW-BTC"));
        Duration elapsed = Duration.ofNanos(System.nanoTime() - startedAt);

        assertThat(tickers).isEmpty();
        assertThat(requestCount.get()).isEqualTo(2);
        // 바로 다시 부르지 않고 설정한 백오프(150ms)만큼은 쉬었다가 불러야 한다.
        assertThat(elapsed).isGreaterThanOrEqualTo(Duration.ofMillis(140));
    }

    @Test
    @DisplayName("429가 재시도에서도 이어지면 UPSTREAM_UNAVAILABLE, 호출은 딱 두 번")
    void tooManyRequestsTwice() throws IOException {
        startScriptedServer(new int[] {429}, "[]");
        UpbitClient client = client(Duration.ofSeconds(1), Duration.ZERO, Duration.ZERO, Duration.ofMillis(50));

        assertUpstreamUnavailable(() -> client.getTickers(List.of("KRW-BTC")));

        assertThat(requestCount.get()).isEqualTo(2);
    }

    @Test
    @DisplayName("418(차단)은 재시도하지 않고 바로 UPSTREAM_UNAVAILABLE")
    void blockedIsNotRetried() throws IOException {
        startScriptedServer(new int[] {418}, "[]");
        UpbitClient client = client(Duration.ofSeconds(1), Duration.ZERO, Duration.ZERO, Duration.ofMillis(50));

        assertUpstreamUnavailable(() -> client.getTickers(List.of("KRW-BTC")));

        assertThat(requestCount.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("429 재시도는 종목 목록·캔들 호출에도 적용된다")
    void retryAppliesToMarketsAndCandles() throws IOException {
        startScriptedServer(new int[] {429, 200, 429, 200}, MARKETS_JSON);
        UpbitClient client = client(Duration.ofSeconds(1), Duration.ZERO, Duration.ZERO, Duration.ofMillis(50));

        assertThat(client.getMarkets()).hasSize(1);
        assertThat(client.getCandles("minutes/1", "KRW-BTC", null, 10)).hasSize(1);

        assertThat(requestCount.get()).isEqualTo(4);
    }

    @Test
    @DisplayName("429 재시도로 받은 현재가도 캐시된다")
    void retriedTickersAreCached() throws IOException {
        startScriptedServer(new int[] {429, 200}, "[]");
        UpbitClient client = client(Duration.ofSeconds(1), Duration.ZERO, Duration.ofMinutes(5),
                Duration.ofMillis(50));

        client.getTickers(List.of("KRW-BTC"));
        client.getTickers(List.of("KRW-BTC"));

        assertThat(requestCount.get()).isEqualTo(2);
    }

    private void assertUpstreamUnavailable(Runnable call) {
        assertThatThrownBy(call::run)
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.UPSTREAM_UNAVAILABLE);
    }

    // 현재가 캐시는 끄고(TTL 0), 429 백오프는 짧게 둔 기본 클라이언트
    private UpbitClient client(Duration readTimeout, Duration marketsCacheTtl) {
        return client(readTimeout, marketsCacheTtl, Duration.ZERO, Duration.ofMillis(50));
    }

    private UpbitClient client(Duration readTimeout, Duration marketsCacheTtl, Duration tickerCacheTtl,
                               Duration backoff) {
        return new UpbitClient(baseUrl(), Duration.ofMillis(500), readTimeout, marketsCacheTtl, tickerCacheTtl,
                backoff);
    }

    private String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    // 모든 요청에 같은 상태·본문을 돌려주는 가짜 업비트 서버. delayMillis만큼 늦게 응답한다.
    private void startServer(int status, String body, long delayMillis) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            requestCount.incrementAndGet();
            try {
                if (delayMillis > 0) {
                    Thread.sleep(delayMillis);
                }
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(status, bytes.length);
                exchange.getResponseBody().write(bytes);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (IOException ignored) {
                // 클라이언트가 타임아웃으로 먼저 끊은 경우
            } finally {
                exchange.close();
            }
        });
        server.start();
    }

    // 요청마다 statuses를 순서대로 돌려주는 가짜 업비트 서버. 목록이 끝나면 마지막 상태를 계속 쓴다.
    // 200이면 okBody, 그 외 상태면 짧은 에러 본문을 준다.
    private void startScriptedServer(int[] statuses, String okBody) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            int index = Math.min(requestCount.getAndIncrement(), statuses.length - 1);
            int status = statuses[index];
            byte[] bytes = (status == 200 ? okBody : "{\"error\":{\"name\":\"error\"}}")
                    .getBytes(StandardCharsets.UTF_8);
            try {
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(status, bytes.length);
                exchange.getResponseBody().write(bytes);
            } finally {
                exchange.close();
            }
        });
        server.start();
    }
}
