package com.ove.auth.dto;

import com.ove.user.dto.UserSummaryResponse;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class RegisterResponse {

    private final UserSummaryResponse user;
}
