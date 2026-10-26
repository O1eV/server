package com.ove.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Schema(description = "회원가입 요청")
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class RegisterRequest {

    @NotBlank
    @Email
    @Size(max = 255)
    @Schema(description = "로그인에 쓸 이메일(앞뒤 공백은 지우고 소문자로 저장). 최대 255자", example = "user@example.com")
    private String email;

    // 영문·숫자 각 1자 이상, 8~64자. BCrypt 입력 한도(72바이트)를 넘지 않도록 ASCII 문자만 허용한다.
    @NotBlank
    @Pattern(regexp = "^(?=.*[A-Za-z])(?=.*\\d)[\\x21-\\x7E]{8,64}$",
            message = "비밀번호는 영문과 숫자를 포함한 8~64자여야 합니다.")
    @Schema(description = "비밀번호. 영문과 숫자를 각각 1자 이상 포함한 8~64자(공백 제외 ASCII 문자)", example = "Passw0rd123")
    private String password;

    @NotBlank
    @Schema(description = "비밀번호 확인. password와 같아야 합니다", example = "Passw0rd123")
    private String passwordConfirm;

    @NotBlank
    @Size(min = 2, max = 20)
    @Schema(description = "닉네임. 2~20자이고 다른 회원과 겹치면 안 됩니다", example = "오브")
    private String nickname;

    @AssertTrue(message = "서비스 이용약관에 동의해야 합니다.")
    @Schema(description = "서비스 이용약관 동의. true여야 합니다", example = "true")
    private boolean termsAgreed;

    @AssertTrue(message = "개인정보 처리방침에 동의해야 합니다.")
    @Schema(description = "개인정보 처리방침 동의. true여야 합니다", example = "true")
    private boolean privacyAgreed;

    @AssertTrue(message = "투자 유의사항에 동의해야 합니다.")
    @Schema(description = "투자 유의사항 동의. true여야 합니다", example = "true")
    private boolean investmentNoticeAgreed;
}
