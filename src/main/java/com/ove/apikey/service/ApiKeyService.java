package com.ove.apikey.service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ove.apikey.domain.ApiKey;
import com.ove.apikey.dto.ApiKeyCreateResponse;
import com.ove.apikey.dto.ApiKeyItemResponse;
import com.ove.apikey.dto.ApiKeyListResponse;
import com.ove.apikey.repository.ApiKeyRepository;
import com.ove.global.exception.BusinessException;
import com.ove.global.exception.ErrorCode;
import com.ove.user.domain.UserStatus;
import com.ove.user.repository.UserRepository;

@Service
public class ApiKeyService {

    private static final int MAX_NAME_LENGTH = 30;

    private final ApiKeyRepository apiKeyRepository;
    private final UserRepository userRepository;
    private final ApiKeyGenerator generator;
    private final int maxActive;

    public ApiKeyService(ApiKeyRepository apiKeyRepository, UserRepository userRepository, ApiKeyGenerator generator,
                         @Value("${api-key.max-active:3}") int maxActive) {
        this.apiKeyRepository = apiKeyRepository;
        this.userRepository = userRepository;
        this.generator = generator;
        this.maxActive = maxActive;
    }

    // 키를 발급할 때 사용. 원문은 이 응답에만 담기고 DB에는 해시와 앞 8자리만 저장된다.
    // 같은 회원의 발급 요청이 동시에 와도 활성 3개를 넘지 않도록 회원 행에 쓰기 잠금을 걸어 한 줄로 세운다.
    @Transactional
    public ApiKeyCreateResponse create(UUID userId, String name) {
        String trimmed = name.trim();
        if (trimmed.isEmpty() || trimmed.length() > MAX_NAME_LENGTH) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "name은 앞뒤 공백을 지운 뒤 1~" + MAX_NAME_LENGTH + "자여야 합니다.");
        }

        userRepository.findByIdForUpdate(userId)
                .filter(user -> user.getStatus() == UserStatus.ACTIVE)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));

        if (apiKeyRepository.countByUserIdAndRevokedAtIsNull(userId) >= maxActive) {
            throw new BusinessException(ErrorCode.API_KEY_LIMIT_EXCEEDED,
                    "API 키는 최대 " + maxActive + "개까지 발급할 수 있습니다. 쓰지 않는 키를 폐기한 뒤 다시 시도해 주세요.");
        }

        String rawKey = generator.generate();
        ApiKey saved = apiKeyRepository.saveAndFlush(ApiKey.builder()
                .userId(userId)
                .name(trimmed)
                .keyHash(generator.hash(rawKey))
                .prefix(generator.prefixOf(rawKey))
                .build());
        return new ApiKeyCreateResponse(saved, rawKey);
    }

    // 내 활성 키 목록(폐기한 키 제외)을 보여 줄 때 사용
    @Transactional(readOnly = true)
    public ApiKeyListResponse list(UUID userId) {
        List<ApiKeyItemResponse> items = apiKeyRepository.findByUserIdAndRevokedAtIsNullOrderByCreatedAtAscIdAsc(userId)
                .stream().map(ApiKeyItemResponse::new).toList();
        return new ApiKeyListResponse(items);
    }

    // 키를 폐기(소프트)할 때 사용. 없는 키·이미 폐기한 키·남의 키는 구분하지 않고 모두 404다.
    // 한 번의 update로 처리해서 동시에 두 번 폐기해도 한 번만 성공하고 나머지는 404가 된다.
    @Transactional
    public void revoke(UUID userId, UUID keyId) {
        if (apiKeyRepository.revoke(keyId, userId, Instant.now()) == 0) {
            throw new BusinessException(ErrorCode.API_KEY_NOT_FOUND);
        }
    }
}
