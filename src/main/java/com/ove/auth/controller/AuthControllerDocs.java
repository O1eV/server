package com.ove.auth.controller;

import org.springframework.http.ResponseEntity;

import com.ove.auth.dto.LoginRequest;
import com.ove.auth.dto.LoginResponse;
import com.ove.auth.dto.RegisterRequest;
import com.ove.auth.dto.RegisterResponse;
import com.ove.auth.dto.TokenResponse;
import com.ove.global.docs.ApiErrorCodes;
import com.ove.global.exception.ErrorCode;
import com.ove.global.response.ApiResponse;
import com.ove.global.security.AuthUser;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;

/**
 * AuthController의 Swagger 문서. 문서 어노테이션이 길어서 컨트롤러 본문(쿠키 처리 등)이 묻히지 않도록 따로 뒀다.
 * 요청 매핑(@PostMapping 등)과 검증(@Valid)은 컨트롤러에 있고, 여기는 문서 전용이다.
 * 파라미터 제약(@Min 등)은 컨트롤러가 아니라 이 인터페이스에만 붙인다(HV000151 방지, ApiErrorCodes 설명 참고).
 */
@Tag(name = "Auth")
public interface AuthControllerDocs {

    @Operation(summary = "회원가입", description = """
            이메일·비밀번호·닉네임으로 가입합니다. **자동 로그인은 하지 않아서 토큰을 돌려주지 않습니다.** 가입한 뒤 로그인 API를 호출하세요.

            가입하면 약관 동의 이력이 저장되고 모의투자 계좌(10,000,000 KRW)가 만들어집니다.
            - 이메일은 앞뒤 공백을 지우고 소문자로 저장합니다(대소문자만 다른 이메일은 중복입니다).
            - 비밀번호는 영문과 숫자를 각각 1자 이상 포함한 8~64자(공백 제외 ASCII 문자)입니다.
            - 서비스 이용약관, 개인정보 처리방침, 투자 유의사항 3가지에 모두 동의(true)해야 합니다.
            """)
    @SecurityRequirements
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "가입 완료. 가입한 회원의 userId·email·nickname을 돌려줍니다.")
    @ApiErrorCodes({ErrorCode.INVALID_REQUEST, ErrorCode.EMAIL_ALREADY_EXISTS, ErrorCode.NICKNAME_ALREADY_EXISTS})
    ResponseEntity<ApiResponse<RegisterResponse>> register(RegisterRequest request);

    @Operation(summary = "로그인", description = """
            이메일·비밀번호로 로그인합니다. 응답 본문에는 **accessToken만** 있고, refreshToken은 `Set-Cookie`로 내려갑니다.

            **refresh 쿠키** — `refreshToken=...; HttpOnly; Secure; SameSite=Lax; Path=/api/auth`
            - `rememberMe=true`: `Max-Age=1209600`(14일) 쿠키라 브라우저를 닫아도 유지됩니다.
            - `rememberMe` 생략 또는 `false`: Max-Age가 없는 세션 쿠키라 브라우저를 닫으면 사라집니다.
            - 브라우저가 쿠키를 자동 저장하고 `/api/auth/**` 요청에만 보냅니다. 프런트는 `fetch`에 `credentials: 'include'`가 필요합니다.
            - Swagger UI에서는 이 요청을 실행하면 브라우저가 쿠키를 저장하고, 이어서 `재발급`·`로그아웃`을 호출하면 자동으로 전달됩니다.

            이메일이 없는 경우와 비밀번호가 틀린 경우, 탈퇴한 계정은 모두 같은 `INVALID_CREDENTIALS`로 응답해 가입 여부를 알 수 없게 합니다.
            """)
    @SecurityRequirements
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "로그인 성공. accessToken(30분)과 회원 정보를 돌려줍니다.",
            headers = @Header(name = "Set-Cookie", description = "refresh 토큰 쿠키(HttpOnly)",
                    schema = @Schema(type = "string",
                            example = "refreshToken=eyJ...; Max-Age=1209600; Path=/api/auth; Secure; HttpOnly; SameSite=Lax")))
    @ApiErrorCodes({ErrorCode.INVALID_REQUEST, ErrorCode.INVALID_CREDENTIALS})
    ResponseEntity<ApiResponse<LoginResponse>> login(LoginRequest request);

    @Operation(summary = "토큰 재발급", description = """
            **요청 본문이 없습니다.** 브라우저가 보내는 `refreshToken` 쿠키로 새 accessToken을 발급하고, 쿠키도 새 refresh 토큰으로 교체합니다(rotation).
            - 한 번 쓴 refresh 토큰은 다시 쓸 수 없습니다. 새 쿠키로 계속 이어서 호출하세요.
            - 로그인 때의 `rememberMe` 설정이 그대로 이어집니다(14일 쿠키 / 세션 쿠키).
            - 쿠키가 없거나, 위조·만료·이미 사용한 토큰이거나, 탈퇴한 회원이면 `INVALID_REFRESH_TOKEN`입니다.
            - CSRF 방어: `Origin` 헤더가 있으면 허용 origin 목록과 같아야 하고, 아니면 `FORBIDDEN`입니다(`Origin`이 없으면 통과).

            Swagger UI에서는 먼저 로그인을 실행해 쿠키를 저장한 뒤 호출하세요(쿠키 값을 직접 넣을 수는 없습니다).
            """)
    @SecurityRequirements(@SecurityRequirement(name = "refreshTokenCookie"))
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "재발급 성공. 새 accessToken을 돌려주고 새 refresh 쿠키를 내려줍니다.",
            headers = @Header(name = "Set-Cookie", description = "새 refresh 토큰 쿠키(HttpOnly)",
                    schema = @Schema(type = "string",
                            example = "refreshToken=eyJ...; Path=/api/auth; Secure; HttpOnly; SameSite=Lax")))
    @ApiErrorCodes({ErrorCode.INVALID_REFRESH_TOKEN, ErrorCode.FORBIDDEN})
    ResponseEntity<ApiResponse<TokenResponse>> refresh(HttpServletRequest request);

    @Operation(summary = "로그아웃", description = """
            **요청 본문이 없습니다.** Bearer 토큰이 필요합니다. 현재 accessToken을 남은 만료 시간만큼 블랙리스트에 올리고,
            `refreshToken` 쿠키의 토큰을 폐기한 뒤 쿠키를 지웁니다(`Max-Age=0`, 이름·경로·속성은 로그인 때와 같음).
            - 쿠키가 없거나 잘못돼도(또는 다른 사용자의 것이어도) 200으로 끝나고 accessToken만 무효가 됩니다.
            - CSRF 방어: `Origin` 헤더가 있으면 허용 origin 목록과 같아야 하고, 아니면 `FORBIDDEN`입니다.
            """)
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "로그아웃 완료(data는 null). refresh 쿠키를 지우는 Set-Cookie가 함께 내려갑니다.",
            headers = @Header(name = "Set-Cookie", description = "refresh 쿠키 삭제(Max-Age=0)",
                    schema = @Schema(type = "string",
                            example = "refreshToken=; Max-Age=0; Path=/api/auth; Secure; HttpOnly; SameSite=Lax")))
    @ApiErrorCodes({ErrorCode.TOKEN_EXPIRED, ErrorCode.UNAUTHORIZED, ErrorCode.FORBIDDEN})
    ResponseEntity<ApiResponse<Void>> logout(AuthUser authUser, HttpServletRequest request);
}
