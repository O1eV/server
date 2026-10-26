package com.ove.user.dto;

import java.time.Instant;
import java.util.UUID;

import com.ove.user.domain.User;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Getter;

/**
 * GET /users/me 응답. user와 stats 두 덩어리로 내려준다.
 */
@Schema(description = "내 정보 조회 응답")
@Getter
@Builder
public class MyInfoResponse {

    @Schema(description = "회원 기본 정보")
    private final UserInfo user;
    @Schema(description = "회원 통계")
    private final Stats stats;

    @Schema(name = "MyInfoUser", description = "회원 기본 정보")
    @Getter
    @Builder
    public static class UserInfo {

        @Schema(description = "회원 ID(UUID)", example = "3f2b8c1e-5a4d-4e6f-9b7a-1c2d3e4f5a6b")
        private final UUID userId;
        @Schema(description = "이메일", example = "user@example.com")
        private final String email;
        @Schema(description = "닉네임", example = "오브")
        private final String nickname;
        @Schema(description = "가입 시각(UTC, ISO-8601)", example = "2026-10-24T03:21:45.123Z")
        private final Instant createdAt;
    }

    @Schema(name = "MyInfoStats", description = "회원 통계(해당 기능이 아직 없어 항상 0)")
    @Getter
    @Builder
    public static class Stats {

        @Schema(description = "관심종목 수", example = "0")
        private final long watchlistCount;
        @Schema(description = "활성 API 키 수", example = "0")
        private final long activeApiKeyCount;
    }

    // API 키 기능이 아직 없어 activeApiKeyCount는 0으로 고정한다.
    public static MyInfoResponse from(User user, long watchlistCount) {
        return MyInfoResponse.builder()
                .user(UserInfo.builder()
                        .userId(user.getId())
                        .email(user.getEmail())
                        .nickname(user.getNickname())
                        .createdAt(user.getCreatedAt())
                        .build())
                .stats(Stats.builder()
                        .watchlistCount(watchlistCount)
                        .activeApiKeyCount(0)
                        .build())
                .build();
    }
}
