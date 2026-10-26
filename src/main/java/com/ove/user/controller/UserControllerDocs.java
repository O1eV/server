package com.ove.user.controller;

import org.springframework.http.ResponseEntity;

import com.ove.global.docs.ApiErrorCodes;
import com.ove.global.exception.ErrorCode;
import com.ove.global.response.ApiResponse;
import com.ove.global.security.AuthUser;
import com.ove.user.dto.MyInfoResponse;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * UserController의 Swagger 문서(문서 전용 인터페이스).
 * 파라미터 제약(@Min 등)은 컨트롤러가 아니라 이 인터페이스에만 붙인다(HV000151 방지, ApiErrorCodes 설명 참고).
 */
@Tag(name = "Users")
public interface UserControllerDocs {

    @Operation(summary = "내 정보 조회", description = """
            로그인한 회원의 기본 정보와 통계를 돌려줍니다. Bearer 토큰이 필요합니다.
            - `stats`(관심종목 수, 활성 API 키 수)는 해당 기능이 아직 없어서 항상 0입니다.
            """)
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "회원 기본 정보(user)와 통계(stats)")
    @ApiErrorCodes({ErrorCode.TOKEN_EXPIRED, ErrorCode.UNAUTHORIZED, ErrorCode.USER_NOT_FOUND})
    ResponseEntity<ApiResponse<MyInfoResponse>> getMyInfo(AuthUser authUser);
}
