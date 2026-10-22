package com.ove.market.collector;

import java.time.Instant;
import java.util.List;

/**
 * 재접속 뒤 빠진 1분봉을 REST로 다시 받아 오는 쪽의 추상화(업비트·키움이 각자 구현한다).
 */
public interface CandleBackfillSource {

    /**
     * 실시간 집계가 시작되는 분(liveFrom) 직전까지, 최근 windowMinutes분 안의 1분봉을 돌려준다.
     * 순서는 상관없다. 창 밖의 봉을 걸러 내는 것은 호출한 쪽(MarketCollector)이다.
     */
    List<MinuteCandle> fetchRecent(String symbol, Instant liveFrom, int windowMinutes);
}
