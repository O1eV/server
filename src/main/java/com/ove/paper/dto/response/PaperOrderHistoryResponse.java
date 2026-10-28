package com.ove.paper.dto.response;

import java.util.List;

import org.springframework.data.domain.Page;

import com.ove.paper.domain.PaperOrder;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class PaperOrderHistoryResponse {

    private final List<PaperOrderResponse> orders;
    private final PageInfo pageInfo;

    public static PaperOrderHistoryResponse from(Page<PaperOrder> orders) {
        return PaperOrderHistoryResponse.builder()
                .orders(orders.getContent().stream().map(PaperOrderResponse::from).toList())
                .pageInfo(PageInfo.builder()
                        .page(orders.getNumber())
                        .size(orders.getSize())
                        .totalElements(orders.getTotalElements())
                        .totalPages(orders.getTotalPages())
                        .build())
                .build();
    }

    @Getter
    @Builder
    public static class PageInfo {

        private final int page;
        private final int size;
        private final long totalElements;
        private final int totalPages;
    }
}
