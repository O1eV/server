package com.ove.support;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

import com.sun.net.httpserver.HttpServer;

/**
 * 테스트용 가짜 키움 REST 서버(JDK 내장 HttpServer). 경로별로 응답을 정해 두고 받은 요청을 기록한다.
 * 실제 키움에는 연결하지 않는다.
 */
public class FakeKiwoomRestServer implements AutoCloseable {

    /** 받은 요청 기록: path, 헤더(소문자 키), 본문 */
    public record Received(String path, Map<String, String> headers, String body) {
    }

    /** 응답: HTTP 상태, 본문, (선택) 응답 헤더 */
    public record Reply(int status, String body, Map<String, String> headers) {

        public static Reply ok(String body) {
            return new Reply(200, body, Map.of());
        }

        public static Reply of(int status, String body) {
            return new Reply(status, body, Map.of());
        }
    }

    private final HttpServer server;
    private final List<Received> requests = new CopyOnWriteArrayList<>();
    private final Map<String, Function<Received, Reply>> handlers = new ConcurrentHashMap<>();

    public FakeKiwoomRestServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            try {
                String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                Map<String, String> headers = new java.util.HashMap<>();
                exchange.getRequestHeaders().forEach((key, values) -> headers.put(key.toLowerCase(), values.get(0)));
                Received received = new Received(exchange.getRequestURI().getPath(), headers, body);
                requests.add(received);

                Function<Received, Reply> handler = handlers.get(received.path());
                Reply reply = handler == null ? Reply.of(404, "{\"return_code\":1504,\"return_msg\":\"not found\"}")
                        : handler.apply(received);
                byte[] bytes = reply.body().getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json;charset=UTF-8");
                reply.headers().forEach((key, value) -> exchange.getResponseHeaders().add(key, value));
                exchange.sendResponseHeaders(reply.status(), bytes.length);
                exchange.getResponseBody().write(bytes);
            } finally {
                exchange.close();
            }
        });
        server.start();
    }

    public String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    /** 경로에 대한 응답을 정한다. 요청마다 호출되므로 호출 횟수에 따라 다르게 줄 수도 있다. */
    public void handle(String path, Function<Received, Reply> handler) {
        handlers.put(path, handler);
    }

    public List<Received> requests() {
        return requests;
    }

    public List<Received> requestsTo(String path) {
        return requests.stream().filter(received -> received.path().equals(path)).toList();
    }

    @Override
    public void close() {
        server.stop(0);
    }

    /** 표준 토큰 발급 응답. 발급마다 token 값이 달라지도록 호출 순번을 붙인다. */
    public static String tokenJson(String token, String expiresDt) {
        return "{\"expires_dt\":\"" + expiresDt + "\",\"token_type\":\"bearer\",\"token\":\"" + token
                + "\",\"return_code\":0,\"return_msg\":\"정상적으로 처리되었습니다\"}";
    }
}
