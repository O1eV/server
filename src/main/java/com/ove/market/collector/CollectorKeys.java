package com.ove.market.collector;

/**
 * 수집기가 Redis에 쓰는 키와 Pub/Sub 채널 이름. 이름을 바꿀 때는 여기만 고친다.
 * (만료 시간은 collector.price-ttl 설정값)
 */
public final class CollectorKeys {

    private CollectorKeys() {
    }

    // 현재가 해시: price, tradeTimestamp, updatedAt
    public static String priceKey(String symbol) {
        return "price:" + symbol;
    }

    // 현재가 tick 채널. 종목당 초당 최대 1회 발행한다.
    public static String tickChannel(String symbol) {
        return "prices:" + symbol;
    }

    // 1분봉이 마감될 때마다 발행하는 채널
    public static String candleClosedChannel(String symbol) {
        return "candles.closed:" + symbol;
    }
}
