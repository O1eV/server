package com.ove.watchlist.dto;

import java.util.List;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public class WatchlistListResponse {

    private final List<WatchlistItemResponse> items;
}
