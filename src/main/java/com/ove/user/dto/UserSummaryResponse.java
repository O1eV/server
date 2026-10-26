package com.ove.user.dto;

import java.util.UUID;

import com.ove.user.domain.User;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Getter;

@Schema(description = "회원 요약 정보")
@Getter
@Builder
public class UserSummaryResponse {

    @Schema(description = "회원 ID(UUID)", example = "3f2b8c1e-5a4d-4e6f-9b7a-1c2d3e4f5a6b")
    private final UUID userId;
    @Schema(description = "이메일", example = "user@example.com")
    private final String email;
    @Schema(description = "닉네임", example = "오브")
    private final String nickname;

    public static UserSummaryResponse from(User user) {
        return UserSummaryResponse.builder()
                .userId(user.getId())
                .email(user.getEmail())
                .nickname(user.getNickname())
                .build();
    }
}
