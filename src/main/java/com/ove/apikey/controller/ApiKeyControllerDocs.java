package com.ove.apikey.controller;

import java.util.UUID;

import org.springframework.http.ResponseEntity;

import com.ove.apikey.dto.ApiKeyCreateRequest;
import com.ove.apikey.dto.ApiKeyCreateResponse;
import com.ove.apikey.dto.ApiKeyListResponse;
import com.ove.global.docs.ApiErrorCodes;
import com.ove.global.exception.ErrorCode;
import com.ove.global.response.ApiResponse;
import com.ove.global.security.AuthUser;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * ApiKeyController의 Swagger 문서(문서 전용 인터페이스). 모든 API가 Bearer 토큰을 요구한다.
 * 파라미터 제약(@Min 등)은 컨트롤러가 아니라 이 인터페이스에만 붙인다(HV000151 방지, ApiErrorCodes 설명 참고).
 */
@Tag(name = "ApiKeys")
public interface ApiKeyControllerDocs {

    @Operation(summary = "API 키 발급", description = """
            외부 연동용 API 키를 발급합니다. Bearer 토큰이 필요합니다.
            - **키 원문(`apiKey`)은 이 응답에서 한 번만 보여 줍니다.** 서버에는 SHA-256 해시와 앞 8자리(`prefix`)만 저장하므로 다시 확인할 수 없습니다.
            - 형식은 `ove_` + 랜덤 32바이트 hex 64자입니다.
            - `name`은 앞뒤 공백을 지운 뒤 1~30자이며 중복을 허용합니다.
            - 활성 키는 회원당 최대 3개입니다. 4번째 발급은 `API_KEY_LIMIT_EXCEEDED`이며, 폐기한 키는 개수에 포함하지 않습니다.
            - 모델 서버 내부 키(`X-API-KEY`)와는 별개입니다.
            """)
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "발급 완료. 키 원문(apiKey)을 돌려줍니다(이때만).")
    @ApiErrorCodes({ErrorCode.INVALID_REQUEST, ErrorCode.TOKEN_EXPIRED, ErrorCode.UNAUTHORIZED,
            ErrorCode.USER_NOT_FOUND, ErrorCode.API_KEY_LIMIT_EXCEEDED})
    ResponseEntity<ApiResponse<ApiKeyCreateResponse>> create(AuthUser authUser, ApiKeyCreateRequest request);

    @Operation(summary = "내 API 키 목록", description = """
            내 **활성** 키만 발급 순서대로 돌려줍니다(폐기한 키는 제외). 키 원문과 해시는 돌려주지 않습니다.
            - `lastUsedAt`은 키 인증 기능이 없어서 지금은 항상 `null`입니다.
            """)
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "내 활성 키 목록(없으면 빈 목록)")
    @ApiErrorCodes({ErrorCode.TOKEN_EXPIRED, ErrorCode.UNAUTHORIZED})
    ResponseEntity<ApiResponse<ApiKeyListResponse>> list(AuthUser authUser);

    @Operation(summary = "API 키 폐기", description = """
            키를 폐기합니다(소프트 폐기: 행은 남기고 폐기 시각만 기록). 폐기한 키는 되살릴 수 없습니다.
            - 없는 키, 이미 폐기한 키, 다른 회원의 키는 구분하지 않고 모두 `API_KEY_NOT_FOUND`입니다.
            """)
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "폐기 완료(data는 null)")
    @ApiErrorCodes({ErrorCode.INVALID_REQUEST, ErrorCode.TOKEN_EXPIRED, ErrorCode.UNAUTHORIZED, ErrorCode.API_KEY_NOT_FOUND})
    ResponseEntity<ApiResponse<Void>> revoke(AuthUser authUser,
                                             @Parameter(description = "폐기할 키 ID(UUID)", example = "3f2b8c1e-5a4d-4e6f-9b7a-1c2d3e4f5a6b") UUID keyId);
}
