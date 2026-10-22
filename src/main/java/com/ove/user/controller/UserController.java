package com.ove.user.controller;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.ove.global.response.ApiResponse;
import com.ove.global.security.AuthUser;
import com.ove.global.security.RefreshCookieFactory;
import com.ove.user.dto.MyInfoResponse;
import com.ove.user.dto.WithdrawRequest;
import com.ove.user.service.UserService;
import com.ove.user.service.UserWithdrawService;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;
    private final UserWithdrawService userWithdrawService;
    private final RefreshCookieFactory refreshCookieFactory;

    @GetMapping("/me")
    public ResponseEntity<ApiResponse<MyInfoResponse>> getMyInfo(@AuthenticationPrincipal AuthUser authUser) {
        return ResponseEntity.ok(ApiResponse.success(HttpStatus.OK, "내 정보 조회에 성공했습니다.",
                userService.getMyInfo(authUser.userId())));
    }

    // 회원탈퇴: 현재 비밀번호를 다시 확인하고, 로그아웃과 같은 방식으로 refresh 쿠키도 지운다.
    @DeleteMapping("/me")
    public ResponseEntity<ApiResponse<Void>> withdraw(@AuthenticationPrincipal AuthUser authUser,
                                                      @Valid @RequestBody WithdrawRequest request) {
        userWithdrawService.withdraw(authUser, request.getPassword());
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, refreshCookieFactory.delete().toString())
                .body(ApiResponse.success(HttpStatus.OK, "회원탈퇴가 완료되었습니다.", null));
    }
}
