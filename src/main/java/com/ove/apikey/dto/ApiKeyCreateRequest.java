package com.ove.apikey.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Schema(description = "API 키 발급 요청")
@Getter
@NoArgsConstructor
public class ApiKeyCreateRequest {

    @Schema(description = "키 이름(앞뒤 공백을 지운 뒤 1~30자, 중복 허용)", example = "내 노트북", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank
    private String name;

    public ApiKeyCreateRequest(String name) {
        this.name = name;
    }
}
