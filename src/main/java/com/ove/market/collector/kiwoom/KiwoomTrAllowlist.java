package com.ove.market.collector.kiwoom;

import java.util.Map;
import java.util.Set;

/**
 * 이 수집기가 호출할 수 있는 키움 TR의 허용 목록. 사용자 결정에 따라 시세 조회만 쓰고,
 * 주문·계좌 TR은 코드에 호출 경로 자체를 만들지 않는다. 목록에 없는 TR은 호출 직전에 거부한다.
 */
public final class KiwoomTrAllowlist {

    /** REST: TR id → 경로. 토큰 발급·폐기와 분봉 차트 조회뿐이다. */
    private static final Map<String, String> REST = Map.of(
            "au10001", "/oauth2/token",
            "au10002", "/oauth2/revoke",
            "ka10080", "/api/dostk/chart");

    /** 실시간: 0B 주식체결, 0s 장시작시간뿐이다. 주문체결(00)·잔고(04) 같은 계좌 TR은 허용하지 않는다. */
    private static final Set<String> REALTIME_TYPES = Set.of("0B", "0s");

    private KiwoomTrAllowlist() {
    }

    public static void requireRest(String apiId, String path) {
        String allowedPath = REST.get(apiId);
        if (allowedPath == null || !allowedPath.equals(path)) {
            throw new IllegalArgumentException("허용되지 않은 키움 REST 호출입니다: apiId=" + apiId + ", path=" + path);
        }
    }

    public static void requireRealtime(String type) {
        if (!REALTIME_TYPES.contains(type)) {
            throw new IllegalArgumentException("허용되지 않은 키움 실시간 TR입니다: type=" + type);
        }
    }
}
