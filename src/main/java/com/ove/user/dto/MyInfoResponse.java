package com.ove.user.dto;

import java.time.Instant;
import java.util.UUID;

import com.ove.user.domain.User;

import lombok.Builder;
import lombok.Getter;

/**
 * GET /users/me 응답. user와 stats 두 덩어리로 내려준다.
 */
@Getter
@Builder
public class MyInfoResponse {

    private final UserInfo user;
    private final Stats stats;

    @Getter
    @Builder
    public static class UserInfo {

        private final UUID userId;
        private final String email;
        private final String nickname;
        private final Instant createdAt;
    }

    @Getter
    @Builder
    public static class Stats {

        private final long watchlistCount;
        private final long activeApiKeyCount;
    }

    // 관심종목·API 키 기능이 아직 없어 통계는 0으로 고정한다.
    public static MyInfoResponse from(User user) {
        return MyInfoResponse.builder()
                .user(UserInfo.builder()
                        .userId(user.getId())
                        .email(user.getEmail())
                        .nickname(user.getNickname())
                        .createdAt(user.getCreatedAt())
                        .build())
                .stats(Stats.builder()
                        .watchlistCount(0)
                        .activeApiKeyCount(0)
                        .build())
                .build();
    }
}
