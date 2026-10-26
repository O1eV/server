package com.ove.watchlist.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
public class WatchlistAddRequest {

    @NotBlank
    private String symbol;

    public WatchlistAddRequest(String symbol) {
        this.symbol = symbol;
    }
}
