package com.ove.paper.dto.response;

import java.math.BigDecimal;
import java.time.Instant;

import com.ove.paper.domain.OrderSide;
import com.ove.paper.domain.PaperOrder;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class PaperOrderResponse {

    private final Long orderId;
    private final String symbol;
    private final OrderSide side;
    private final BigDecimal quantity;
    private final BigDecimal price;
    private final BigDecimal amount;
    private final Instant orderedAt;

    public static PaperOrderResponse from(PaperOrder order) {
        return PaperOrderResponse.builder()
                .orderId(order.getId())
                .symbol(order.getSymbol())
                .side(order.getSide())
                .quantity(order.getQuantity())
                .price(order.getPrice())
                .amount(order.getAmount())
                .orderedAt(order.getOrderedAt())
                .build();
    }
}
