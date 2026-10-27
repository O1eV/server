package com.ove.market.collector.kiwoom;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Map;

import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.JsonNode;

/**
 * 접근토큰 발급(au10001)·폐기(au10002)와 만료 관리.
 * expires_dt는 KST 기준 yyyyMMddHHmmss다. 만료 직전(tokenRefreshMargin)이면 다음에 쓸 때 새로 발급받는다.
 * 토큰은 로그에 남기지 않는다.
 */
@Slf4j
public class KiwoomTokenProvider implements AutoCloseable {

    static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final DateTimeFormatter EXPIRES_DT = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    private final KiwoomRestClient restClient;
    private final String appKey;
    private final String appSecret;
    private final Duration refreshMargin;
    private final Clock clock;

    private String token;
    private Instant expiresAt;

    public KiwoomTokenProvider(KiwoomRestClient restClient, KiwoomProperties properties, Clock clock) {
        this.restClient = restClient;
        this.appKey = properties.appKey();
        this.appSecret = properties.appSecret();
        this.refreshMargin = properties.tokenRefreshMargin();
        this.clock = clock;
    }

    /**
     * 쓸 수 있는 접근토큰. 없거나 만료 직전이면 새로 발급받는다.
     */
    public synchronized String token() {
        if (token == null || !clock.instant().isBefore(expiresAt.minus(refreshMargin))) {
            issue();
        }
        return token;
    }

    /**
     * 서버가 토큰을 거부했을 때(8005 등) 불러서 다음 token() 호출이 새로 발급받게 한다.
     * 거부된 토큰이 서버에 살아 있을 수 있어서 폐기(au10002)를 한 번 시도한다(실패해도 무시).
     * 재발급이 계속 반복되며 토큰이 쌓이는 것을 막으려는 것이고, 반복 속도는 재연결 백오프가 제한한다.
     */
    public synchronized void invalidate() {
        revokeQuietly();
        token = null;
        expiresAt = null;
    }

    private void issue() {
        Map<String, Object> body = Map.of("grant_type", "client_credentials", "appkey", appKey, "secretkey", appSecret);
        JsonNode response = restClient.post("au10001", "/oauth2/token", null, body, null).body();

        String issued = response.path("token").asString("");
        String expiresDt = response.path("expires_dt").asString("");
        if (issued.isBlank()) {
            throw new KiwoomException(null, "키움 토큰 발급 응답에 token이 없습니다.");
        }
        Instant expiry;
        try {
            expiry = LocalDateTime.parse(expiresDt, EXPIRES_DT).atZone(KST).toInstant();
        } catch (DateTimeParseException e) {
            throw new KiwoomException(null, "키움 토큰 발급 응답의 expires_dt 형식이 올바르지 않습니다.");
        }
        this.token = issued;
        this.expiresAt = expiry;
        log.info("키움 접근토큰 발급: 만료={}", expiry);
    }

    /**
     * 애플리케이션을 끌 때 토큰을 폐기한다(토큰은 발급받은 IP에 묶이므로 쓰지 않을 토큰을 남기지 않는다).
     * 실패해도 종료를 막지 않는다.
     */
    @Override
    public synchronized void close() {
        revokeQuietly();
        token = null;
        expiresAt = null;
    }

    private void revokeQuietly() {
        if (token == null) {
            return;
        }
        try {
            restClient.post("au10002", "/oauth2/revoke", token,
                    Map.of("appkey", appKey, "secretkey", appSecret, "token", token), null);
            log.info("키움 접근토큰 폐기");
        } catch (RuntimeException e) {
            log.warn("키움 접근토큰 폐기 실패(무시): {}", e.getMessage());
        }
    }
}
