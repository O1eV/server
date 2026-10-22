package com.ove.market.collector;

import java.time.Duration;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * application.yaml의 collector.* 설정. 시세 수집기는 enabled=true일 때만 켜진다.
 */
@ConfigurationProperties(prefix = "collector")
public record CollectorProperties(
        @DefaultValue("false") boolean enabled,
        @DefaultValue({"KRW-BTC", "KRW-ETH", "KRW-XRP"}) List<String> symbols,
        @DefaultValue("wss://api.upbit.com/websocket/v1") String webSocketUrl,
        // 분이 끝난 뒤 이 시간이 지나야 봉을 마감한다(늦게 도착하는 체결을 기다리는 시간)
        @DefaultValue("1500ms") Duration graceDelay,
        // 봉 마감·tick 발행 여부를 확인하는 주기
        @DefaultValue("250ms") Duration tickCheckInterval,
        // 같은 종목의 tick은 이 간격보다 자주 발행하지 않는다(초당 최대 1회)
        @DefaultValue("1s") Duration tickPublishInterval,
        // Redis price:{symbol} 해시의 만료 시간. 수집기가 죽으면 오래된 가격이 남지 않게 한다.
        @DefaultValue("10m") Duration priceTtl,
        @DefaultValue("1s") Duration reconnectInitialDelay,
        @DefaultValue("30s") Duration reconnectMaxDelay,
        // 업비트 연결 요청 한도(초당 5회)를 넘지 않게 하는 재연결 간격 하한
        @DefaultValue("250ms") Duration reconnectMinDelay,
        // 업비트는 120초 동안 주고받는 게 없으면 연결을 끊으므로 그 전에 ping을 보낸다
        @DefaultValue("60s") Duration pingInterval,
        // 이 시간 동안 아무것도 못 받으면 끊긴 연결로 보고 다시 연결한다(ping의 pong도 수신으로 센다)
        @DefaultValue("90s") Duration idleTimeout,
        // 유휴 상태인지 확인하는 주기. ping 주기와 따로 둬서, 끊김 감지가 idleTimeout + 이 값 안에 끝나게 한다.
        @DefaultValue("10s") Duration idleCheckInterval,
        // 재연결 뒤 누락 구간 REST 보충이 실패했을 때 다시 시도하는 간격과 횟수
        @DefaultValue("5s") Duration backfillRetryDelay,
        @DefaultValue("3") int backfillMaxAttempts
) {

    public List<String> upperCaseSymbols() {
        return symbols.stream().map(String::trim).map(String::toUpperCase).toList();
    }
}
