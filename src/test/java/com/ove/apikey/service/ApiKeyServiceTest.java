package com.ove.apikey.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import com.ove.apikey.domain.ApiKey;
import com.ove.apikey.dto.ApiKeyCreateResponse;
import com.ove.apikey.dto.ApiKeyListResponse;
import com.ove.apikey.repository.ApiKeyRepository;
import com.ove.global.exception.BusinessException;
import com.ove.global.exception.ErrorCode;
import com.ove.user.domain.User;
import com.ove.user.repository.UserRepository;

@ExtendWith(MockitoExtension.class)
class ApiKeyServiceTest {

    private static final UUID USER_ID = UUID.randomUUID();

    @Mock
    private ApiKeyRepository apiKeyRepository;

    @Mock
    private UserRepository userRepository;

    private final ApiKeyGenerator generator = new ApiKeyGenerator();
    private ApiKeyService service;

    @BeforeEach
    void setUp() {
        service = new ApiKeyService(apiKeyRepository, userRepository, generator, 3);
    }

    @Test
    @DisplayName("발급: 원문은 응답에만 담기고 저장되는 것은 해시·prefix·trim한 이름뿐이다")
    void createStoresOnlyHashAndPrefix() {
        givenActiveUser();
        given(apiKeyRepository.saveAndFlush(any(ApiKey.class))).willAnswer(invocation -> {
            ApiKey key = invocation.getArgument(0);
            ReflectionTestUtils.setField(key, "id", UUID.randomUUID());
            ReflectionTestUtils.setField(key, "createdAt", Instant.parse("2026-10-26T01:00:00Z"));
            return key;
        });

        ApiKeyCreateResponse response = service.create(USER_ID, "  내 노트북  ");

        assertThat(response.getApiKey()).matches("ove_[0-9a-f]{64}");
        assertThat(response.getName()).isEqualTo("내 노트북");
        assertThat(response.getPrefix()).isEqualTo(response.getApiKey().substring(0, 8));
        ArgumentCaptor<ApiKey> captor = ArgumentCaptor.forClass(ApiKey.class);
        verify(apiKeyRepository).saveAndFlush(captor.capture());
        ApiKey saved = captor.getValue();
        assertThat(saved.getUserId()).isEqualTo(USER_ID);
        assertThat(saved.getName()).isEqualTo("내 노트북");
        assertThat(saved.getKeyHash()).isEqualTo(generator.hash(response.getApiKey()));
        assertThat(saved.getPrefix()).isEqualTo(response.getPrefix());
        // 저장되는 어떤 필드에도 원문이 들어 있지 않다
        assertThat(saved.getKeyHash()).doesNotContain(response.getApiKey());
        assertThat(saved.getName()).doesNotContain(response.getApiKey());
    }

    @Test
    @DisplayName("발급: 활성 키가 이미 3개면 API_KEY_LIMIT_EXCEEDED이고 저장하지 않는다, 2개면 발급된다")
    void createLimit() {
        givenActiveUser();
        given(apiKeyRepository.countByUserIdAndRevokedAtIsNull(USER_ID)).willReturn(3L);

        assertThatThrownBy(() -> service.create(USER_ID, "키"))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo(ErrorCode.API_KEY_LIMIT_EXCEEDED);
                    assertThat(e.getMessage()).contains("최대 3개");
                });
        verify(apiKeyRepository, never()).saveAndFlush(any());

        given(apiKeyRepository.countByUserIdAndRevokedAtIsNull(USER_ID)).willReturn(2L);
        given(apiKeyRepository.saveAndFlush(any(ApiKey.class))).willAnswer(i -> i.getArgument(0));
        assertThat(service.create(USER_ID, "키").getName()).isEqualTo("키");
    }

    @Test
    @DisplayName("발급: 이름이 공백뿐이거나 trim 후 30자를 넘으면 INVALID_REQUEST (30자는 통과)")
    void createNameValidation() {
        assertInvalid("   ");
        assertInvalid("a".repeat(31));
        assertInvalid("  " + "a".repeat(31) + "  ");
        verifyNoInteractions(userRepository, apiKeyRepository);

        givenActiveUser();
        given(apiKeyRepository.saveAndFlush(any(ApiKey.class))).willAnswer(i -> i.getArgument(0));
        assertThat(service.create(USER_ID, "  " + "a".repeat(30) + "  ").getName()).hasSize(30);
    }

    @Test
    @DisplayName("발급: 회원이 없거나 탈퇴했으면 USER_NOT_FOUND")
    void createUserMissingOrWithdrawn() {
        given(userRepository.findByIdForUpdate(USER_ID)).willReturn(Optional.empty());
        assertThatThrownBy(() -> service.create(USER_ID, "키"))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.USER_NOT_FOUND));

        User withdrawn = User.builder().email("a@ove.com").password("x").nickname("에이").build();
        ReflectionTestUtils.setField(withdrawn, "id", USER_ID);
        withdrawn.withdraw(Instant.now());
        given(userRepository.findByIdForUpdate(USER_ID)).willReturn(Optional.of(withdrawn));
        assertThatThrownBy(() -> service.create(USER_ID, "키"))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.USER_NOT_FOUND));
        verify(apiKeyRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("목록: 활성 키만 원문·해시 없이 돌려주고 lastUsedAt은 null")
    void list() {
        ApiKey key = ApiKey.builder().userId(USER_ID).name("키").keyHash("h".repeat(64)).prefix("ove_abcd").build();
        ReflectionTestUtils.setField(key, "id", UUID.randomUUID());
        given(apiKeyRepository.findByUserIdAndRevokedAtIsNullOrderByCreatedAtAscIdAsc(USER_ID)).willReturn(List.of(key));

        ApiKeyListResponse response = service.list(USER_ID);

        assertThat(response.getItems()).hasSize(1);
        assertThat(response.getItems().get(0).getPrefix()).isEqualTo("ove_abcd");
        assertThat(response.getItems().get(0).getLastUsedAt()).isNull();
    }

    @Test
    @DisplayName("폐기: 한 건 폐기하면 정상, 0건(없음·이미 폐기·남의 키)이면 API_KEY_NOT_FOUND")
    void revoke() {
        UUID keyId = UUID.randomUUID();
        given(apiKeyRepository.revoke(eq(keyId), eq(USER_ID), any(Instant.class))).willReturn(1);
        service.revoke(USER_ID, keyId);

        given(apiKeyRepository.revoke(eq(keyId), eq(USER_ID), any(Instant.class))).willReturn(0);
        assertThatThrownBy(() -> service.revoke(USER_ID, keyId))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.API_KEY_NOT_FOUND));
    }

    private void givenActiveUser() {
        given(userRepository.findByIdForUpdate(USER_ID)).willReturn(Optional.of(
                User.builder().email("a@ove.com").password("x").nickname("에이").build()));
    }

    private void assertInvalid(String name) {
        assertThatThrownBy(() -> service.create(USER_ID, name))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.INVALID_REQUEST));
    }
}
