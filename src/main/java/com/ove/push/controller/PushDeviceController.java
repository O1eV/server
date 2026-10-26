package com.ove.push.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.ove.global.response.ApiResponse;
import com.ove.global.security.AuthUser;
import com.ove.push.dto.PushDeviceRequest;
import com.ove.push.dto.PushDeviceResponse;
import com.ove.push.service.PushDeviceService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@Tag(name = "Push Devices", description = "신호 푸시 알림을 받을 기기 등록")
@RestController
@RequestMapping("/api/devices")
@RequiredArgsConstructor
public class PushDeviceController {

    private final PushDeviceService pushDeviceService;

    @Operation(summary = "푸시 기기 등록",
            description = "앱에서 받은 FCM/APNs 토큰을 등록합니다. 같은 토큰으로 다시 호출하면 플랫폼·알림 설정만 갱신합니다. "
                    + "이미 다른 사용자에게 등록된 토큰이면 409입니다.")
    @PostMapping
    public ResponseEntity<ApiResponse<PushDeviceResponse>> register(
            @AuthenticationPrincipal AuthUser authUser, @Valid @RequestBody PushDeviceRequest request) {
        return ResponseEntity.ok(ApiResponse.success(HttpStatus.OK, "푸시 기기를 등록했습니다.",
                pushDeviceService.register(authUser.userId(), request)));
    }
}
