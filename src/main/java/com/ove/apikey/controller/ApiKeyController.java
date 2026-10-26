package com.ove.apikey.controller;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.ove.apikey.dto.ApiKeyCreateRequest;
import com.ove.apikey.dto.ApiKeyCreateResponse;
import com.ove.apikey.dto.ApiKeyListResponse;
import com.ove.apikey.service.ApiKeyService;
import com.ove.global.response.ApiResponse;
import com.ove.global.security.AuthUser;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/keys")
@RequiredArgsConstructor
public class ApiKeyController implements ApiKeyControllerDocs {

    private final ApiKeyService apiKeyService;

    @Override
    @PostMapping
    public ResponseEntity<ApiResponse<ApiKeyCreateResponse>> create(@AuthenticationPrincipal AuthUser authUser,
                                                                    @Valid @RequestBody ApiKeyCreateRequest request) {
        ApiKeyCreateResponse response = apiKeyService.create(authUser.userId(), request.getName());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(HttpStatus.CREATED, "API 키가 발급되었습니다.", response));
    }

    @Override
    @GetMapping
    public ResponseEntity<ApiResponse<ApiKeyListResponse>> list(@AuthenticationPrincipal AuthUser authUser) {
        return ResponseEntity.ok(ApiResponse.success(HttpStatus.OK, "요청에 성공했습니다.",
                apiKeyService.list(authUser.userId())));
    }

    @Override
    @DeleteMapping("/{keyId}")
    public ResponseEntity<ApiResponse<Void>> revoke(@AuthenticationPrincipal AuthUser authUser,
                                                    @PathVariable UUID keyId) {
        apiKeyService.revoke(authUser.userId(), keyId);
        return ResponseEntity.ok(ApiResponse.success(HttpStatus.OK, "API 키가 폐기되었습니다.", null));
    }
}
