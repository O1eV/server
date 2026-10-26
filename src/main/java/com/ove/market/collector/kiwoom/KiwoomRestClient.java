package com.ove.market.collector.kiwoom;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Map;

import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 키움 REST 호출기. 허용 목록(KiwoomTrAllowlist)에 있는 TR만 호출하고, 응답의 return_code가 0이 아니면
 * KiwoomException으로 바꾼다. 요청 본문·Authorization 헤더·응답 본문은 로그에 남기지 않는다(키·토큰이 들어 있다).
 */
@Slf4j
public class KiwoomRestClient {

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final Duration minInterval;
    private long lastCallNanos;

    public KiwoomRestClient(String baseUrl, Duration connectTimeout, Duration readTimeout, Duration minInterval,
                            ObjectMapper objectMapper) {
        HttpClient httpClient = HttpClient.newBuilder().connectTimeout(connectTimeout).build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(readTimeout);
        this.restClient = RestClient.builder().baseUrl(baseUrl).requestFactory(requestFactory).build();
        this.objectMapper = objectMapper;
        this.minInterval = minInterval;
    }

    /**
     * @param bearerToken 접근토큰. 토큰 발급(au10001)처럼 필요 없으면 null
     * @param nextKey     이어서 조회할 때 이전 응답의 next-key. 처음이면 null
     */
    public KiwoomResponse post(String apiId, String path, String bearerToken, Map<String, Object> body,
                               String nextKey) {
        KiwoomTrAllowlist.requireRest(apiId, path);
        throttle();

        // 본문에는 App Key·Secret·토큰이 들어 있다. RestClient에 객체(Map)를 넘기면 DEBUG 로그가 본문을 그대로 남기므로,
        // 직접 직렬화한 바이트를 스트림으로 써서 그 로그 경로를 피한다. 스트림 본문은 길이를 모르면 chunked로 가므로
        // Content-Length를 직접 지정한다.
        byte[] json = objectMapper.writeValueAsBytes(body);

        RawResponse raw;
        try {
            raw = restClient.post().uri(path)
                    .contentType(MediaType.APPLICATION_JSON)
                    .headers(headers -> {
                        headers.setContentLength(json.length);
                        headers.set("api-id", apiId);
                        if (bearerToken != null) {
                            headers.set("authorization", "Bearer " + bearerToken);
                        }
                        if (nextKey != null) {
                            headers.set("cont-yn", "Y");
                            headers.set("next-key", nextKey);
                        }
                    })
                    .body(out -> out.write(json))
                    .exchange((request, response) -> new RawResponse(response.getStatusCode().value(),
                            response.getHeaders().getFirst("cont-yn"), response.getHeaders().getFirst("next-key"),
                            response.bodyTo(String.class)));
        } catch (RestClientException e) {
            // 연결 실패·타임아웃. 원인 메시지에는 URL만 있고 비밀 값은 없다.
            throw new KiwoomException(null, "키움 서버에 연결하지 못했습니다: " + e.getClass().getSimpleName());
        }
        return parse(apiId, raw);
    }

    private KiwoomResponse parse(String apiId, RawResponse raw) {
        JsonNode json = null;
        if (raw.body() != null && !raw.body().isBlank()) {
            try {
                json = objectMapper.readTree(raw.body());
            } catch (RuntimeException e) {
                // 본문이 JSON이 아닌 경우. 아래에서 HTTP 상태로 처리한다.
            }
        }

        // 오류는 HTTP 오류 상태로 올 수도, 200 본문의 return_code로 올 수도 있어서 둘 다 본다.
        if (json != null && json.has("return_code")) {
            int code = json.path("return_code").asInt(-1);
            if (code != 0) {
                throw KiwoomException.of(code, json.path("return_msg").asString(""));
            }
        } else if (raw.status() >= 400) {
            throw new KiwoomException(null, "키움 API가 HTTP " + raw.status() + "를 돌려줬습니다 (apiId=" + apiId + ")");
        }
        if (json == null) {
            throw new KiwoomException(null, "키움 API 응답을 해석할 수 없습니다 (apiId=" + apiId + ")");
        }
        return new KiwoomResponse(json, "Y".equalsIgnoreCase(raw.contYn()), raw.nextKey());
    }

    // 호출 사이 최소 간격을 지킨다(초당 호출 한도 보호). 한도를 넘으면 서버가 1700 계열 오류를 준다.
    private synchronized void throttle() {
        long now = System.nanoTime();
        long waitNanos = lastCallNanos + minInterval.toNanos() - now;
        if (lastCallNanos != 0 && waitNanos > 0) {
            try {
                Thread.sleep(waitNanos / 1_000_000, (int) (waitNanos % 1_000_000));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new KiwoomException(null, "키움 REST 호출 대기 중 중단되었습니다.");
            }
        }
        lastCallNanos = System.nanoTime();
    }

    private record RawResponse(int status, String contYn, String nextKey, String body) {
    }

    /** 성공 응답. hasNext가 true면 nextKey로 이어서 조회할 수 있다. */
    public record KiwoomResponse(JsonNode body, boolean hasNext, String nextKey) {
    }
}
