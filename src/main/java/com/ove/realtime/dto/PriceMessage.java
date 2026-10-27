package com.ove.realtime.dto;

import java.math.BigDecimal;
import java.time.Instant;

import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
public class PriceMessage {

    private final String type = "PRICE";
    private BigDecimal changeRate;
    private BigDecimal volume;
    private String symbol;
    private BigDecimal price;
    private Instant time;

    public PriceMessage(String symbol, BigDecimal price, BigDecimal changeRate, BigDecimal volume, Instant time) {
        this(symbol, price, time);
        this.changeRate = changeRate;
        this.volume = volume;
    }

    // /topic/prices/{symbol}로 보낼 현재가 메시지를 만들 때 사용
    public PriceMessage(String symbol, BigDecimal price, Instant time) {
        this.symbol = symbol;
        this.price = price;
        this.time = time;
    }
}
