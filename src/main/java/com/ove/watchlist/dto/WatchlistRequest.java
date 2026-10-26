package com.ove.watchlist.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record WatchlistRequest(@NotBlank @Size(max = 30) String symbol) {}
