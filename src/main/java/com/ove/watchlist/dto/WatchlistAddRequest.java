package com.ove.watchlist.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
public class WatchlistAddRequest {

    @NotBlank(message = "종목 심볼을 입력해 주세요.")
    private String symbol;
}
