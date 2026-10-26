package com.ove.apikey.dto;

import java.time.Instant;
import java.util.UUID;

import com.ove.apikey.domain.ApiKey;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;

@Schema(description = "API 키 한 건. 원문과 해시는 포함하지 않습니다.")
@Getter
public class ApiKeyItemResponse {

    @Schema(description = "키 ID", example = "3f2b8c1e-5a4d-4e6f-9b7a-1c2d3e4f5a6b")
    private final UUID keyId;
    @Schema(description = "키 이름", example = "내 노트북")
    private final String name;
    @Schema(description = "키 앞 8자리", example = "ove_0123")
    private final String prefix;
    @Schema(description = "발급 시각(UTC, ISO-8601)", example = "2026-10-26T03:21:45.123Z")
    private final Instant createdAt;
    @Schema(description = "마지막 사용 시각. 키 인증 기능이 없어 지금은 항상 null입니다.", nullable = true)
    private final Instant lastUsedAt;

    public ApiKeyItemResponse(ApiKey apiKey) {
        this.keyId = apiKey.getId();
        this.name = apiKey.getName();
        this.prefix = apiKey.getPrefix();
        this.createdAt = apiKey.getCreatedAt();
        this.lastUsedAt = apiKey.getLastUsedAt();
    }
}
