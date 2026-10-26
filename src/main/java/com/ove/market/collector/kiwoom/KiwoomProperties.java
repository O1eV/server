package com.ove.market.collector.kiwoom;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * application.yaml의 collector.kiwoom.* 설정. 키움 주식 시세 수집기는 enabled=true일 때만 켜진다.
 * App Key·Secret은 환경변수(KIWOOM_APP_KEY, KIWOOM_APP_SECRET)로만 받고, 값을 로그나 문자열로 내보내지 않는다.
 */
@ConfigurationProperties(prefix = "collector.kiwoom")
public record KiwoomProperties(
        @DefaultValue("false") boolean enabled,
        // 수집할 종목 코드(6자리). 실시간 등록은 합계 200종목까지(총괄이 준 공식 FAQ 기준, 이 저장소 자료로는 미확인)
        @DefaultValue("005930") List<String> symbols,
        String appKey,
        String appSecret,
        // 실전: https://api.kiwoom.com, 모의: https://mockapi.kiwoom.com
        @DefaultValue("https://api.kiwoom.com") String restBaseUrl,
        // 실전: wss://api.kiwoom.com:10000/api/dostk/websocket, 모의: wss://mockapi.kiwoom.com:10000/api/dostk/websocket
        @DefaultValue("wss://api.kiwoom.com:10000/api/dostk/websocket") String webSocketUrl,
        // 토큰 만료 이 시간 전부터는 새로 발급받는다
        @DefaultValue("10m") Duration tokenRefreshMargin,
        // 장중에 이 시간 동안 아무 메시지도 못 받으면 죽은 연결로 보고 다시 연결한다(장외에는 적용하지 않는다)
        @DefaultValue("120s") Duration idleTimeout,
        // 연결 후 LOGIN·종목 등록이 이 시간 안에 끝나지 않으면 다시 연결한다
        @DefaultValue("15s") Duration setupTimeout,
        // REST 호출 사이 최소 간격(키움은 TR당 토큰별 초당 5건이라고 알려져 있어 여유 있게 둔다)
        @DefaultValue("250ms") Duration restMinInterval,
        @DefaultValue("500ms") Duration restConnectTimeout,
        @DefaultValue("3s") Duration restReadTimeout,
        // 재접속 뒤 분봉 REST(ka10080) 보충 사용 여부. 분봉의 시각이 봉 시작인지 끝인지 공식 자료로 확인되지 않아 기본은 끔
        @DefaultValue("false") boolean backfillEnabled,
        // backfillEnabled=true일 때 필수: ka10080 응답의 cntr_tm이 봉의 START(시작) 시각인지 END(끝) 시각인지
        String minuteBarLabel,
        // ka10080 upd_stkpc_tp(수정주가구분) 값. 공식 요청 예시와 같은 "1"
        @DefaultValue("1") String adjustedPriceType
) {

    private static final Pattern SYMBOL = Pattern.compile("[0-9A-Z]{6}");
    private static final int MAX_SYMBOLS = 200;
    private static final Set<String> LOOPBACK_HOSTS = Set.of("localhost", "127.0.0.1", "[::1]");

    /** 비밀 값이 로그·예외 메시지에 새지 않도록 키와 시크릿은 가려서 보여 준다. */
    @Override
    public String toString() {
        return "KiwoomProperties[enabled=" + enabled + ", symbols=" + symbols
                + ", appKey=" + mask(appKey) + ", appSecret=" + mask(appSecret)
                + ", restBaseUrl=" + restBaseUrl + ", webSocketUrl=" + webSocketUrl
                + ", backfillEnabled=" + backfillEnabled + ", minuteBarLabel=" + minuteBarLabel + "]";
    }

    private static String mask(String value) {
        return value == null || value.isBlank() ? "<비어 있음>" : "<설정됨>";
    }

    public List<String> normalizedSymbols() {
        return symbols.stream().map(String::trim).map(String::toUpperCase).toList();
    }

    /**
     * 켜져 있을 때 설정이 쓸 수 있는지 확인한다. 실패 메시지에는 설정 이름만 쓰고 값은 쓰지 않는다.
     */
    public void validate() {
        if (appKey == null || appKey.isBlank() || appSecret == null || appSecret.isBlank()) {
            throw new IllegalStateException("키움 수집기를 켰지만 환경변수 KIWOOM_APP_KEY 또는 KIWOOM_APP_SECRET이 비어 있습니다.");
        }
        List<String> normalized = normalizedSymbols();
        if (normalized.isEmpty() || normalized.size() > MAX_SYMBOLS) {
            throw new IllegalStateException("collector.kiwoom.symbols는 1~" + MAX_SYMBOLS + "개여야 합니다.");
        }
        for (String symbol : normalized) {
            // 6자리 종목코드만 허용한다(거래소 접미사 _NX, _AL 등은 허용하지 않는다)
            if (!SYMBOL.matcher(symbol).matches()) {
                throw new IllegalStateException("collector.kiwoom.symbols에 올바르지 않은 종목 코드가 있습니다: " + symbol
                        + " (6자리 코드만 허용, 접미사 _NX/_AL 불가)");
            }
        }
        // 키와 토큰이 오가므로 암호화된 연결(https, wss)만 허용한다. 로컬 가짜 서버 테스트용으로 loopback 주소만 예외다.
        if (!isSecure(restBaseUrl, "https") || !isSecure(webSocketUrl, "wss")) {
            throw new IllegalStateException("collector.kiwoom.rest-base-url은 https://, web-socket-url은 wss://로 시작해야 합니다.");
        }
        if (backfillEnabled && !("START".equalsIgnoreCase(minuteBarLabel) || "END".equalsIgnoreCase(minuteBarLabel))) {
            throw new IllegalStateException("collector.kiwoom.backfill-enabled=true이면 collector.kiwoom.minute-bar-label을 "
                    + "START 또는 END로 지정해야 합니다(분봉 시각이 봉의 시작인지 끝인지 먼저 확인하세요).");
        }
    }

    // 암호화된 주소(secureScheme)이거나, 호스트가 정확히 loopback인 평문 주소(http/ws)만 통과시킨다.
    // 문자열 접두사로 비교하면 http://localhost.example.com 같은 주소가 통과해서 키와 토큰이 평문으로 나갈 수 있다.
    private static boolean isSecure(String url, String secureScheme) {
        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException e) {
            return false;
        }
        String host = uri.getHost();
        if (uri.getScheme() == null || host == null) {
            return false;
        }
        if (secureScheme.equalsIgnoreCase(uri.getScheme())) {
            return true;
        }
        String plainScheme = "https".equals(secureScheme) ? "http" : "ws";
        return plainScheme.equalsIgnoreCase(uri.getScheme()) && LOOPBACK_HOSTS.contains(host.toLowerCase());
    }

    public boolean minuteBarLabelIsEnd() {
        return "END".equalsIgnoreCase(minuteBarLabel);
    }
}
