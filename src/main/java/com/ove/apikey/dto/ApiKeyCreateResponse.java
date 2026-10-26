package com.ove.apikey.dto;

import java.time.Instant;
import java.util.UUID;

import com.ove.apikey.domain.ApiKey;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;

@Schema(description = "API 키 발급 응답. 키 원문(apiKey)은 이 응답에서만 볼 수 있습니다.")
@Getter
public class ApiKeyCreateResponse {

    @Schema(description = "키 ID", example = "3f2b8c1e-5a4d-4e6f-9b7a-1c2d3e4f5a6b")
    private final UUID keyId;
    @Schema(description = "키 이름", example = "내 노트북")
    private final String name;
    @Schema(description = "키 원문(ove_ + 64자 hex). 다시 조회할 수 없으니 지금 저장하세요.",
            example = "ove_0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef")
    private final String apiKey;
    @Schema(description = "키 앞 8자리", example = "ove_0123")
    private final String prefix;
    @Schema(description = "발급 시각(UTC, ISO-8601)", example = "2026-10-26T03:21:45.123Z")
    private final Instant createdAt;

    public ApiKeyCreateResponse(ApiKey apiKey, String rawKey) {
        this.keyId = apiKey.getId();
        this.name = apiKey.getName();
        this.apiKey = rawKey;
        this.prefix = apiKey.getPrefix();
        this.createdAt = apiKey.getCreatedAt();
    }
}
