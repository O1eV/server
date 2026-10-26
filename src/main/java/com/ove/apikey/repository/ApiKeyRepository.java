package com.ove.apikey.repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.ove.apikey.domain.ApiKey;

public interface ApiKeyRepository extends JpaRepository<ApiKey, UUID> {

    // 폐기하지 않은(활성) 키 개수
    long countByUserIdAndRevokedAtIsNull(UUID userId);

    // 활성 키만, 발급 순서(오래된 것부터)
    List<ApiKey> findByUserIdAndRevokedAtIsNullOrderByCreatedAtAscIdAsc(UUID userId);

    // 한 번의 update로 폐기하고 폐기한 건수를 돌려준다. 없는 키·이미 폐기한 키·남의 키는 0건이라 동시에 두 번 눌러도 한 번만 성공한다.
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update ApiKey k set k.revokedAt = :now where k.id = :id and k.userId = :userId and k.revokedAt is null")
    int revoke(@Param("id") UUID id, @Param("userId") UUID userId, @Param("now") Instant now);

    // 회원탈퇴 때 활성 키를 전부 폐기하는 용도
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update ApiKey k set k.revokedAt = :now where k.userId = :userId and k.revokedAt is null")
    int revokeAllByUserId(@Param("userId") UUID userId, @Param("now") Instant now);
}
