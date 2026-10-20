package com.ove.market.client;

import java.math.BigDecimal;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class UpbitTickerResponse {

    private String market;

    @JsonProperty("trade_price")
    private BigDecimal tradePrice;

    private String change;

    @JsonProperty("signed_change_price")
    private BigDecimal signedChangePrice;

    @JsonProperty("signed_change_rate")
    private BigDecimal signedChangeRate;

    @JsonProperty("acc_trade_price_24h")
    private BigDecimal accTradePrice24h;
}
