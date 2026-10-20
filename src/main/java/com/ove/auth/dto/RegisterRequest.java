package com.ove.auth.dto;

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

@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class RegisterRequest {

    @NotBlank
    @Email
    @Size(max = 255)
    private String email;

    // 영문·숫자 각 1자 이상, 8~64자. BCrypt 입력 한도(72바이트)를 넘지 않도록 ASCII 문자만 허용한다.
    @NotBlank
    @Pattern(regexp = "^(?=.*[A-Za-z])(?=.*\\d)[\\x21-\\x7E]{8,64}$",
            message = "비밀번호는 영문과 숫자를 포함한 8~64자여야 합니다.")
    private String password;

    @NotBlank
    private String passwordConfirm;

    @NotBlank
    @Size(min = 2, max = 20)
    private String nickname;

    @AssertTrue(message = "서비스 이용약관에 동의해야 합니다.")
    private boolean termsAgreed;

    @AssertTrue(message = "개인정보 처리방침에 동의해야 합니다.")
    private boolean privacyAgreed;

    @AssertTrue(message = "투자 유의사항에 동의해야 합니다.")
    private boolean investmentNoticeAgreed;
}
