package com.ove.paper.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import com.ove.paper.domain.PaperOrder;
import org.springframework.data.domain.Page;

public record OrderHistoryResponse(List<Item> items, int page, int size, long totalElements) {
    public record Item(Long orderId, String symbol, String side, BigDecimal quantity, BigDecimal price,
                       BigDecimal fee, String signalAtOrder, Instant executedAt) {
        static Item from(PaperOrder order) {
            return new Item(order.getId(), order.getSymbol(), order.getSide().name(), order.getQuantity(),
                order.getPrice(), order.getFee(), order.getSignalAtOrder(), order.getOrderedAt());
        }
    }
    public static OrderHistoryResponse from(Page<PaperOrder> value) {
        return new OrderHistoryResponse(value.getContent().stream().map(Item::from).toList(),
            value.getNumber(), value.getSize(), value.getTotalElements());
    }
}
