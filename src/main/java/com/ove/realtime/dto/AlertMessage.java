package com.ove.realtime.dto;

import java.math.BigDecimal;
import java.time.Instant;

import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
public class AlertMessage {

    private String type;
    private String symbol;
    private String from;
    private String to;
    private BigDecimal confidence;
    private Instant time;

    // 관심 종목 신호가 바뀌었다고 /user/queue/alerts로 알려 줄 메시지를 만들 때 사용
    public AlertMessage(String symbol, String from, String to, BigDecimal confidence, Instant time) {
        this.type = "SIGNAL_CHANGED";
        this.symbol = symbol;
        this.from = from;
        this.to = to;
        this.confidence = confidence;
        this.time = time;
    }
}
