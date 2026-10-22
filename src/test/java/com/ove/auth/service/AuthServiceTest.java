package com.ove.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import com.ove.account.service.PaperAccountService;
import com.ove.auth.dto.LoginRequest;
import com.ove.auth.dto.LoginResponse;
import com.ove.auth.dto.RegisterRequest;
import com.ove.auth.dto.RegisterResponse;
import com.ove.auth.dto.TokenResponse;
import com.ove.auth.repository.TokenRedisRepository;
import com.ove.global.exception.BusinessException;
import com.ove.global.exception.ErrorCode;
import com.ove.global.security.AuthUser;
import com.ove.global.security.IssuedToken;
import com.ove.global.security.JwtProperties;
import com.ove.global.security.JwtProvider;
import com.ove.user.domain.TermsAgreement;
import com.ove.user.domain.TermsType;
import com.ove.user.domain.User;
import com.ove.user.repository.TermsAgreementRepository;
import com.ove.user.repository.UserRepository;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    private static final String SECRET = "test-secret-key-for-ove-backend-must-be-at-least-32-bytes";

    @Mock
    private UserRepository userRepository;
    @Mock
    private TermsAgreementRepository termsAgreementRepository;
    @Mock
    private PaperAccountService paperAccountService;
    @Mock
    private PasswordEncoder passwordEncoder;
    @Mock
    private TokenRedisRepository tokenRedisRepository;

    private final JwtProvider jwtProvider = new JwtProvider(
            new JwtProperties(SECRET, Duration.ofMinutes(30), Duration.ofDays(14)));

    private AuthService authService;

    @BeforeEach
    void setUp() {
        authService = new AuthService(userRepository, termsAgreementRepository, paperAccountService,
                passwordEncoder, jwtProvider, tokenRedisRepository);
    }

    // ---------- register ----------

    @Test
    void 회원가입_성공시_해시된_비밀번호로_저장하고_약관3건과_모의계좌를_만든다() {
        given(userRepository.existsByEmail("user@ove.com")).willReturn(false);
        given(userRepository.existsByNickname("오브")).willReturn(false);
        given(passwordEncoder.encode("password123")).willReturn("hashed");
        UUID userId = UUID.randomUUID();
        given(userRepository.save(any(User.class))).willAnswer(invocation -> {
            User saved = invocation.getArgument(0);
            ReflectionTestUtils.setField(saved, "id", userId);
            return saved;
        });

        RegisterResponse response = authService.register(registerRequest(" User@OVE.com ", "password123",
                "password123", "오브"));

        ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(userCaptor.capture());
        assertThat(userCaptor.getValue().getEmail()).isEqualTo("user@ove.com");
        assertThat(userCaptor.getValue().getPassword()).isEqualTo("hashed");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<TermsAgreement>> termsCaptor = ArgumentCaptor.forClass(List.class);
        verify(termsAgreementRepository).saveAll(termsCaptor.capture());
        assertThat(termsCaptor.getValue())
                .extracting(TermsAgreement::getTermsType)
                .containsExactlyInAnyOrder(TermsType.TERMS_OF_SERVICE, TermsType.PRIVACY_POLICY,
                        TermsType.INVESTMENT_NOTICE);
        assertThat(termsCaptor.getValue()).allMatch(TermsAgreement::isAgreed);

        verify(paperAccountService).createInitialAccount(userCaptor.getValue());

        assertThat(response.getUser().getUserId()).isEqualTo(userId);
        assertThat(response.getUser().getEmail()).isEqualTo("user@ove.com");
        assertThat(response.getUser().getNickname()).isEqualTo("오브");
    }

    @Test
    void 회원가입_비밀번호_확인_불일치면_INVALID_REQUEST() {
        assertThatThrownBy(() -> authService.register(registerRequest("user@ove.com", "password123",
                "password999", "오브")))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.INVALID_REQUEST);
        verify(userRepository, never()).save(any());
    }

    @Test
    void 회원가입_이메일_중복이면_EMAIL_ALREADY_EXISTS() {
        given(userRepository.existsByEmail("user@ove.com")).willReturn(true);

        assertThatThrownBy(() -> authService.register(registerRequest("user@ove.com", "password123",
                "password123", "오브")))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.EMAIL_ALREADY_EXISTS);
        verify(userRepository, never()).save(any());
    }

    @Test
    void 회원가입_닉네임_중복이면_NICKNAME_ALREADY_EXISTS() {
        given(userRepository.existsByEmail("user@ove.com")).willReturn(false);
        given(userRepository.existsByNickname("오브")).willReturn(true);

        assertThatThrownBy(() -> authService.register(registerRequest("user@ove.com", "password123",
                "password123", "오브")))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.NICKNAME_ALREADY_EXISTS);
        verify(userRepository, never()).save(any());
    }

    // ---------- login ----------

    @Test
    void 로그인_성공시_토큰쌍을_발급하고_refresh_해시를_저장한다() {
        User user = user(UUID.randomUUID());
        given(userRepository.findByEmail("user@ove.com")).willReturn(Optional.of(user));
        given(passwordEncoder.matches("password123", "hashed")).willReturn(true);

        AuthResult<LoginResponse> result = authService.login(loginRequest("USER@ove.com", "password123", false));

        LoginResponse response = result.body();
        assertThat(response.getExpiresIn()).isEqualTo(1800);
        assertThat(response.getUser().getUserId()).isEqualTo(user.getId());
        String refreshJti = jwtProvider.parse(result.refreshToken()).getId();
        verify(tokenRedisRepository).saveRefreshToken(user.getId(), refreshJti, sha256(result.refreshToken()),
                Duration.ofDays(14));
        assertThat(jwtProvider.parse(response.getAccessToken()).getSubject()).isEqualTo(user.getId().toString());
    }

    @Test
    void 로그인_rememberMe는_결과와_refresh_토큰_claim에_담긴다() {
        User user = user(UUID.randomUUID());
        given(userRepository.findByEmail("user@ove.com")).willReturn(Optional.of(user));
        given(passwordEncoder.matches("password123", "hashed")).willReturn(true);

        AuthResult<LoginResponse> remembered = authService.login(loginRequest("user@ove.com", "password123", true));
        AuthResult<LoginResponse> session = authService.login(loginRequest("user@ove.com", "password123", false));

        assertThat(remembered.rememberMe()).isTrue();
        assertThat(jwtProvider.isRememberMe(jwtProvider.parse(remembered.refreshToken()))).isTrue();
        assertThat(session.rememberMe()).isFalse();
        assertThat(jwtProvider.isRememberMe(jwtProvider.parse(session.refreshToken()))).isFalse();
    }

    @Test
    void 로그인_없는_이메일이면_INVALID_CREDENTIALS() {
        given(userRepository.findByEmail("none@ove.com")).willReturn(Optional.empty());

        assertThatThrownBy(() -> authService.login(loginRequest("none@ove.com", "password123")))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.INVALID_CREDENTIALS);
    }

    @Test
    void 로그인_비밀번호가_틀리면_INVALID_CREDENTIALS() {
        given(userRepository.findByEmail("user@ove.com")).willReturn(Optional.of(user(UUID.randomUUID())));
        given(passwordEncoder.matches("wrong", "hashed")).willReturn(false);

        assertThatThrownBy(() -> authService.login(loginRequest("user@ove.com", "wrong")))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.INVALID_CREDENTIALS);
        verify(tokenRedisRepository, never()).saveRefreshToken(any(), anyString(), anyString(), any());
    }

    // ---------- refresh ----------

    @Test
    void 재발급_성공시_기존_refresh를_지우고_새_쌍을_저장한다() {
        UUID userId = UUID.randomUUID();
        IssuedToken oldRefresh = jwtProvider.createRefreshToken(userId, false);
        given(tokenRedisRepository.findRefreshToken(userId, oldRefresh.jti()))
                .willReturn(Optional.of(sha256(oldRefresh.token())));
        given(tokenRedisRepository.deleteRefreshToken(userId, oldRefresh.jti())).willReturn(true);
        given(userRepository.existsById(userId)).willReturn(true);

        AuthResult<TokenResponse> result = authService.refresh(oldRefresh.token());

        assertThat(result.body().getExpiresIn()).isEqualTo(1800);
        verify(tokenRedisRepository).deleteRefreshToken(userId, oldRefresh.jti());
        String newJti = jwtProvider.parse(result.refreshToken()).getId();
        assertThat(newJti).isNotEqualTo(oldRefresh.jti());
        verify(tokenRedisRepository).saveRefreshToken(eq(userId), eq(newJti), eq(sha256(result.refreshToken())),
                any());
    }

    @Test
    void 재발급해도_rememberMe_모드가_그대로_이어진다() {
        for (boolean rememberMe : new boolean[] {true, false}) {
            UUID userId = UUID.randomUUID();
            IssuedToken oldRefresh = jwtProvider.createRefreshToken(userId, rememberMe);
            given(tokenRedisRepository.findRefreshToken(userId, oldRefresh.jti()))
                    .willReturn(Optional.of(sha256(oldRefresh.token())));
            given(tokenRedisRepository.deleteRefreshToken(userId, oldRefresh.jti())).willReturn(true);
            given(userRepository.existsById(userId)).willReturn(true);

            AuthResult<TokenResponse> result = authService.refresh(oldRefresh.token());

            assertThat(result.rememberMe()).isEqualTo(rememberMe);
            assertThat(jwtProvider.isRememberMe(jwtProvider.parse(result.refreshToken()))).isEqualTo(rememberMe);
        }
    }

    @Test
    void 재발급_쿠키가_없어_토큰이_null이면_INVALID_REFRESH_TOKEN() {
        assertInvalidRefresh(null);
    }

    @Test
    void 재발급_Redis에_없는_토큰이면_INVALID_REFRESH_TOKEN() {
        UUID userId = UUID.randomUUID();
        IssuedToken refresh = jwtProvider.createRefreshToken(userId, false);
        given(tokenRedisRepository.findRefreshToken(userId, refresh.jti())).willReturn(Optional.empty());

        assertInvalidRefresh(refresh.token());
    }

    @Test
    void 재발급_저장된_해시와_다르면_INVALID_REFRESH_TOKEN() {
        UUID userId = UUID.randomUUID();
        IssuedToken refresh = jwtProvider.createRefreshToken(userId, false);
        given(tokenRedisRepository.findRefreshToken(userId, refresh.jti())).willReturn(Optional.of("other-hash"));

        assertInvalidRefresh(refresh.token());
        verify(tokenRedisRepository, never()).deleteRefreshToken(any(), anyString());
    }

    @Test
    void 재발급_동시요청으로_이미_삭제됐으면_INVALID_REFRESH_TOKEN() {
        UUID userId = UUID.randomUUID();
        IssuedToken refresh = jwtProvider.createRefreshToken(userId, false);
        given(tokenRedisRepository.findRefreshToken(userId, refresh.jti()))
                .willReturn(Optional.of(sha256(refresh.token())));
        given(tokenRedisRepository.deleteRefreshToken(userId, refresh.jti())).willReturn(false);

        assertInvalidRefresh(refresh.token());
    }

    @Test
    void 재발급_access_토큰을_보내면_INVALID_REFRESH_TOKEN() {
        assertInvalidRefresh(jwtProvider.createAccessToken(UUID.randomUUID()).token());
    }

    @Test
    void 재발급_만료된_토큰이면_INVALID_REFRESH_TOKEN() {
        JwtProvider expiredProvider = new JwtProvider(
                new JwtProperties(SECRET, Duration.ofMinutes(30), Duration.ofSeconds(-10)));

        assertInvalidRefresh(expiredProvider.createRefreshToken(UUID.randomUUID(), false).token());
    }

    @Test
    void 재발급_형식이_깨진_토큰이면_INVALID_REFRESH_TOKEN() {
        assertInvalidRefresh("garbage");
    }

    // ---------- logout ----------

    @Test
    void 로그아웃시_access를_블랙리스트에_넣고_refresh를_지운다() {
        UUID userId = UUID.randomUUID();
        IssuedToken access = jwtProvider.createAccessToken(userId);
        IssuedToken refresh = jwtProvider.createRefreshToken(userId, false);
        AuthUser authUser = new AuthUser(userId, access.jti(), access.expiresAt());

        authService.logout(authUser, refresh.token());

        ArgumentCaptor<Duration> ttlCaptor = ArgumentCaptor.forClass(Duration.class);
        verify(tokenRedisRepository).addToBlacklist(eq(access.jti()), ttlCaptor.capture());
        assertThat(ttlCaptor.getValue()).isPositive().isLessThanOrEqualTo(Duration.ofMinutes(30));
        verify(tokenRedisRepository).deleteRefreshToken(userId, refresh.jti());
    }

    @Test
    void 로그아웃시_다른_사용자의_refresh는_지우지_않는다() {
        UUID userId = UUID.randomUUID();
        AuthUser authUser = new AuthUser(userId, "access-jti", Instant.now().plusSeconds(600));
        IssuedToken othersRefresh = jwtProvider.createRefreshToken(UUID.randomUUID(), false);

        authService.logout(authUser, othersRefresh.token());

        verify(tokenRedisRepository).addToBlacklist(eq("access-jti"), any());
        verify(tokenRedisRepository, never()).deleteRefreshToken(any(), anyString());
    }

    @Test
    void 로그아웃시_refresh가_잘못돼도_access는_블랙리스트에_넣는다() {
        AuthUser authUser = new AuthUser(UUID.randomUUID(), "access-jti", Instant.now().plusSeconds(600));

        authService.logout(authUser, "garbage");

        verify(tokenRedisRepository).addToBlacklist(eq("access-jti"), any());
        verify(tokenRedisRepository, never()).deleteRefreshToken(any(), anyString());
    }

    @Test
    void 로그아웃시_refresh_쿠키가_없어도_access는_블랙리스트에_넣는다() {
        AuthUser authUser = new AuthUser(UUID.randomUUID(), "access-jti", Instant.now().plusSeconds(600));

        authService.logout(authUser, null);

        verify(tokenRedisRepository).addToBlacklist(eq("access-jti"), any());
        verify(tokenRedisRepository, never()).deleteRefreshToken(any(), anyString());
    }

    private void assertInvalidRefresh(String token) {
        assertThatThrownBy(() -> authService.refresh(token))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.INVALID_REFRESH_TOKEN);
        verify(tokenRedisRepository, never()).saveRefreshToken(any(), anyString(), anyString(), any());
    }

    private RegisterRequest registerRequest(String email, String password, String passwordConfirm, String nickname) {
        return RegisterRequest.builder()
                .email(email)
                .password(password)
                .passwordConfirm(passwordConfirm)
                .nickname(nickname)
                .termsAgreed(true)
                .privacyAgreed(true)
                .investmentNoticeAgreed(true)
                .build();
    }

    private LoginRequest loginRequest(String email, String password) {
        return loginRequest(email, password, false);
    }

    private LoginRequest loginRequest(String email, String password, boolean rememberMe) {
        return LoginRequest.builder().email(email).password(password).rememberMe(rememberMe).build();
    }

    private User user(UUID id) {
        User user = User.builder().email("user@ove.com").password("hashed").nickname("오브").build();
        ReflectionTestUtils.setField(user, "id", id);
        return user;
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
