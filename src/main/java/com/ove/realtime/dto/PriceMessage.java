package com.ove.realtime.dto;

import java.math.BigDecimal;
import java.time.Instant;

import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
public class PriceMessage {

    private String symbol;
    private BigDecimal price;
    private Instant time;

    // /topic/prices/{symbol}로 보낼 현재가 메시지를 만들 때 사용
    public PriceMessage(String symbol, BigDecimal price, Instant time) {
        this.symbol = symbol;
        this.price = price;
        this.time = time;
    }
}
