package com.ove.user.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.ove.global.response.ApiResponse;
import com.ove.global.security.AuthUser;
import com.ove.user.dto.MyInfoResponse;
import com.ove.user.service.UserService;

import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;

    @GetMapping("/me")
    public ResponseEntity<ApiResponse<MyInfoResponse>> getMyInfo(@AuthenticationPrincipal AuthUser authUser) {
        return ResponseEntity.ok(ApiResponse.success(HttpStatus.OK, "내 정보 조회에 성공했습니다.",
                userService.getMyInfo(authUser.userId())));
    }
}
