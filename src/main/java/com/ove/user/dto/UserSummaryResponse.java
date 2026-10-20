package com.ove.user.dto;

import java.util.UUID;

import com.ove.user.domain.User;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class UserSummaryResponse {

    private final UUID userId;
    private final String email;
    private final String nickname;

    public static UserSummaryResponse from(User user) {
        return UserSummaryResponse.builder()
                .userId(user.getId())
                .email(user.getEmail())
                .nickname(user.getNickname())
                .build();
    }
}
