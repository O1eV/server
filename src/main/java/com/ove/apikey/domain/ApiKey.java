package com.ove.apikey.domain;

import java.time.Instant;
import java.util.UUID;

import org.hibernate.annotations.CreationTimestamp;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 외부 연동용 사용자 API 키. 키 원문은 저장하지 않고 SHA-256 해시와 앞 8자리(prefix)만 저장한다.
 * 폐기는 행을 지우지 않고 revokedAt을 채우는 소프트 폐기다. 모델 서버 내부 키(X-API-KEY)와는 별개다.
 * 회원 삭제 시 한 번에 폐기할 수 있도록 User 연관관계 없이 user_id만 들고 있다.
 */
@Entity
@Table(name = "api_keys", indexes = @Index(name = "idx_api_keys_user_id", columnList = "user_id"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ApiKey {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(nullable = false, length = 30)
    private String name;

    // 키 원문의 SHA-256 (hex 64자). 나중에 X-API-KEY 인증 때 이 값으로 찾는다.
    @Column(nullable = false, unique = true, length = 64)
    private String keyHash;

    // 키 원문의 앞 8자리("ove_" + 4자). 목록에서 어떤 키인지 알아보는 용도
    @Column(nullable = false, length = 8)
    private String prefix;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    // 인증 필터가 없는 지금은 갱신하지 않아 항상 null이다.
    private Instant lastUsedAt;

    private Instant revokedAt;

    @Builder
    private ApiKey(UUID userId, String name, String keyHash, String prefix) {
        this.userId = userId;
        this.name = name;
        this.keyHash = keyHash;
        this.prefix = prefix;
    }
}
