package com.ove.market.collector;

import java.time.Instant;
import java.util.List;

import com.ove.market.client.UpbitClient;

/**
 * 업비트 분봉 REST(minutes/1)로 보충한다. 호출은 종목당 1회다.
 */
public class UpbitCandleBackfillSource implements CandleBackfillSource {

    private static final String MINUTE_CANDLE_TYPE = "minutes/1";

    private final UpbitClient upbitClient;

    public UpbitCandleBackfillSource(UpbitClient upbitClient) {
        this.upbitClient = upbitClient;
    }

    @Override
    public List<MinuteCandle> fetchRecent(String symbol, Instant liveFrom, int windowMinutes) {
        // 업비트의 to는 그 시각을 포함하지 않으므로 liveFrom을 넘기면 직전 분까지 받는다.
        return upbitClient.getCandles(MINUTE_CANDLE_TYPE, symbol, liveFrom, windowMinutes).stream()
                .map(candle -> new MinuteCandle(symbol, candle.toInstant(), candle.getOpeningPrice(),
                        candle.getHighPrice(), candle.getLowPrice(), candle.getTradePrice(),
                        candle.getCandleAccTradeVolume()))
                .toList();
    }
}
