package com.ove.apikey.dto;

import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Schema(description = "내 활성 API 키 목록")
@Getter
@RequiredArgsConstructor
public class ApiKeyListResponse {

    @Schema(description = "활성 키 목록(발급 순서)")
    private final List<ApiKeyItemResponse> items;
}
