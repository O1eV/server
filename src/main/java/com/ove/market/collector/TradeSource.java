package com.ove.market.collector;

import java.time.Instant;

/**
 * 체결을 받아오는 쪽의 추상화. 지금은 업비트 WebSocket 하나뿐이고,
 * 나중에 주식(KIS)을 붙일 때 같은 인터페이스로 구현하면 수집기는 그대로 쓴다.
 */
public interface TradeSource {

    void start(TradeListener listener);

    void stop();

    interface TradeListener {

        // 연결하고 구독까지 끝났을 때. 재연결할 때도 불린다.
        void onConnected(Instant connectedAt);

        void onTrade(Trade trade);

        // 연결이 끊겼을 때. 소스가 알아서 다시 연결한다.
        void onDisconnected();
    }
}
