package com.ove.auth.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
import com.ove.global.security.JwtProvider;
import com.ove.global.security.TokenType;
import com.ove.user.domain.TermsAgreement;
import com.ove.user.domain.TermsType;
import com.ove.user.domain.User;
import com.ove.user.dto.UserSummaryResponse;
import com.ove.user.repository.TermsAgreementRepository;
import com.ove.user.repository.UserRepository;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import lombok.RequiredArgsConstructor;

/**
 * 회원가입, 로그인, 토큰 재발급(rotation), 로그아웃.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AuthService {

    private final UserRepository userRepository;
    private final TermsAgreementRepository termsAgreementRepository;
    private final PaperAccountService paperAccountService;
    private final PasswordEncoder passwordEncoder;
    private final JwtProvider jwtProvider;
    private final TokenRedisRepository tokenRedisRepository;

    /**
     * 회원 저장 + 약관 동의 이력 + 모의계좌 생성을 한 트랜잭션으로 묶는다.
     * 자동 로그인은 하지 않으므로 토큰은 발급하지 않는다.
     */
    @Transactional
    public RegisterResponse register(RegisterRequest request) {
        if (!request.getPassword().equals(request.getPasswordConfirm())) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "비밀번호 확인이 일치하지 않습니다.");
        }

        String email = normalizeEmail(request.getEmail());
        String nickname = request.getNickname().trim();
        if (userRepository.existsByEmail(email)) {
            throw new BusinessException(ErrorCode.EMAIL_ALREADY_EXISTS);
        }
        if (userRepository.existsByNickname(nickname)) {
            throw new BusinessException(ErrorCode.NICKNAME_ALREADY_EXISTS);
        }

        User user = userRepository.save(User.builder()
                .email(email)
                .password(passwordEncoder.encode(request.getPassword()))
                .nickname(nickname)
                .build());

        termsAgreementRepository.saveAll(List.of(
                agreement(user, TermsType.TERMS_OF_SERVICE, request.isTermsAgreed()),
                agreement(user, TermsType.PRIVACY_POLICY, request.isPrivacyAgreed()),
                agreement(user, TermsType.INVESTMENT_NOTICE, request.isInvestmentNoticeAgreed())));

        paperAccountService.createInitialAccount(user);

        return RegisterResponse.builder()
                .user(UserSummaryResponse.from(user))
                .build();
    }

    // 이메일이 없는 경우와 비밀번호가 틀린 경우를 같은 코드로 응답해 가입 여부를 노출하지 않는다.
    // refresh 토큰은 응답 본문이 아니라 쿠키로 내려가므로, 컨트롤러가 쿠키를 만들 수 있게 따로 돌려준다.
    public AuthResult<LoginResponse> login(LoginRequest request) {
        User user = userRepository.findByEmail(normalizeEmail(request.getEmail()))
                .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_CREDENTIALS));

        if (!passwordEncoder.matches(request.getPassword(), user.getPassword())) {
            throw new BusinessException(ErrorCode.INVALID_CREDENTIALS);
        }

        Tokens tokens = issueTokens(user.getId(), request.isRememberMe());
        LoginResponse response = LoginResponse.builder()
                .accessToken(tokens.accessToken())
                .expiresIn(tokens.expiresIn())
                .user(UserSummaryResponse.from(user))
                .build();
        return new AuthResult<>(response, tokens.refreshToken(), request.isRememberMe());
    }

    /**
     * refresh 토큰 rotation: 서명·만료 검증 → Redis 저장값과 일치 확인 → 기존 토큰 폐기 → 새 쌍 발급.
     * 어느 단계에서 실패해도 INVALID_REFRESH_TOKEN 하나로 응답한다(쿠키가 없어 null이 와도 마찬가지).
     * 로그인 상태 유지(rememberMe) 여부는 refresh 토큰 claim에서 읽어 새 토큰에도 그대로 이어 준다.
     */
    public AuthResult<TokenResponse> refresh(String refreshToken) {
        Claims claims = parseRefreshToken(refreshToken);
        boolean rememberMe = jwtProvider.isRememberMe(claims);
        UUID userId = UUID.fromString(claims.getSubject());
        String jti = claims.getId();

        String storedHash = tokenRedisRepository.findRefreshToken(userId, jti)
                .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_REFRESH_TOKEN));
        if (!MessageDigest.isEqual(storedHash.getBytes(StandardCharsets.UTF_8),
                hash(refreshToken).getBytes(StandardCharsets.UTF_8))) {
            throw new BusinessException(ErrorCode.INVALID_REFRESH_TOKEN);
        }
        // 동시에 같은 토큰으로 요청이 들어와도 삭제에 성공한 한 건만 새 토큰을 받는다.
        if (!tokenRedisRepository.deleteRefreshToken(userId, jti)) {
            throw new BusinessException(ErrorCode.INVALID_REFRESH_TOKEN);
        }
        if (!userRepository.existsById(userId)) {
            throw new BusinessException(ErrorCode.INVALID_REFRESH_TOKEN);
        }

        Tokens tokens = issueTokens(userId, rememberMe);
        TokenResponse response = TokenResponse.builder()
                .accessToken(tokens.accessToken())
                .expiresIn(tokens.expiresIn())
                .build();
        return new AuthResult<>(response, tokens.refreshToken(), rememberMe);
    }

    /**
     * 현재 access 토큰은 남은 만료 시간만큼 블랙리스트에 올리고, 함께 받은 refresh 토큰은 Redis에서 지운다.
     * refresh 토큰이 이미 만료됐거나 다른 사용자 것이면 지울 대상이 없으므로 무시하고 로그아웃을 마친다.
     */
    public void logout(AuthUser authUser, String refreshToken) {
        Duration remaining = Duration.between(Instant.now(), authUser.accessTokenExpiresAt());
        tokenRedisRepository.addToBlacklist(authUser.accessTokenJti(), remaining);

        try {
            Claims claims = parseRefreshToken(refreshToken);
            if (authUser.userId().toString().equals(claims.getSubject())) {
                tokenRedisRepository.deleteRefreshToken(authUser.userId(), claims.getId());
            }
        } catch (BusinessException ignored) {
            // 폐기할 refresh 토큰이 없는 경우
        }
    }

    private Tokens issueTokens(UUID userId, boolean rememberMe) {
        IssuedToken accessToken = jwtProvider.createAccessToken(userId);
        IssuedToken refreshToken = jwtProvider.createRefreshToken(userId, rememberMe);

        // 토큰 원문 대신 해시를 저장해 Redis가 노출돼도 토큰을 바로 쓸 수 없게 한다.
        // rememberMe=false여도 서버 쪽 만료는 refresh 토큰과 같게 둔다(브라우저의 세션 쿠키만 먼저 사라진다).
        tokenRedisRepository.saveRefreshToken(userId, refreshToken.jti(), hash(refreshToken.token()),
                jwtProvider.getRefreshTokenExpiration());

        return new Tokens(accessToken.token(), refreshToken.token(), jwtProvider.getAccessTokenExpiration().toSeconds());
    }

    // 발급 직후 컨트롤러에 넘길 토큰 묶음. 응답 본문(accessToken)과 쿠키(refreshToken)로 나뉘어 나간다.
    private record Tokens(String accessToken, String refreshToken, long expiresIn) {
    }

    private Claims parseRefreshToken(String refreshToken) {
        try {
            Claims claims = jwtProvider.parse(refreshToken);
            if (jwtProvider.getTokenType(claims) != TokenType.REFRESH
                    || claims.getId() == null || claims.getSubject() == null) {
                throw new BusinessException(ErrorCode.INVALID_REFRESH_TOKEN);
            }
            UUID.fromString(claims.getSubject());
            return claims;
        } catch (JwtException | IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.INVALID_REFRESH_TOKEN);
        }
    }

    private TermsAgreement agreement(User user, TermsType termsType, boolean agreed) {
        return TermsAgreement.builder()
                .user(user)
                .termsType(termsType)
                .agreed(agreed)
                .build();
    }

    // 대소문자만 다른 이메일로 중복 가입하지 못하게 소문자로 저장·조회한다.
    private String normalizeEmail(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }

    private String hash(String token) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            // SHA-256은 모든 JVM이 반드시 제공하는 알고리즘이다.
            throw new IllegalStateException(e);
        }
    }
}
