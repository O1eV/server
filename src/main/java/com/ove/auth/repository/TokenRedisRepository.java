package com.ove.auth.repository;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

import lombok.RequiredArgsConstructor;

/**
 * Redis에 refresh 토큰과 access 토큰 블랙리스트를 저장한다.
 *
 * - refresh:{userId}:{jti} = refresh 토큰의 SHA-256 해시 (TTL = refresh 만료까지)
 *   기기마다 jti가 달라 다중 기기 로그인을 허용한다.
 * - blacklist:{jti} = 로그아웃된 access 토큰 (TTL = access 남은 만료 시간)
 * - withdrawn:{userId} = 탈퇴한 회원 (TTL = access 토큰 최대 수명). 탈퇴 전에 발급돼 다른 기기에 남은 access를 막는다.
 */
@Repository
@RequiredArgsConstructor
public class TokenRedisRepository {

    private static final String REFRESH_KEY_PREFIX = "refresh:";
    private static final String BLACKLIST_KEY_PREFIX = "blacklist:";
    private static final String BLACKLIST_VALUE = "logout";
    private static final String WITHDRAWN_KEY_PREFIX = "withdrawn:";
    private static final String WITHDRAWN_VALUE = "withdrawn";

    private final StringRedisTemplate redisTemplate;

    public void saveRefreshToken(UUID userId, String jti, String tokenHash, Duration ttl) {
        redisTemplate.opsForValue().set(refreshKey(userId, jti), tokenHash, ttl);
    }

    public Optional<String> findRefreshToken(UUID userId, String jti) {
        return Optional.ofNullable(redisTemplate.opsForValue().get(refreshKey(userId, jti)));
    }

    /**
     * 삭제에 성공했을 때만 true. 같은 refresh 토큰으로 동시에 재발급을 요청해도
     * 한 요청만 true를 받으므로 rotation이 한 번만 일어난다.
     */
    public boolean deleteRefreshToken(UUID userId, String jti) {
        return Boolean.TRUE.equals(redisTemplate.delete(refreshKey(userId, jti)));
    }

    /**
     * 사용자의 refresh 토큰을 기기 수와 상관없이 전부 지운다(refresh:{userId}:*).
     * KEYS 대신 SCAN으로 훑어서 Redis를 오래 붙잡지 않는다. 지운 개수를 돌려준다.
     */
    public int deleteAllRefreshTokens(UUID userId) {
        ScanOptions options = ScanOptions.scanOptions().match(REFRESH_KEY_PREFIX + userId + ":*").count(100).build();
        List<String> keys = new ArrayList<>();
        try (Cursor<String> cursor = redisTemplate.scan(options)) {
            cursor.forEachRemaining(keys::add);
        }
        if (keys.isEmpty()) {
            return 0;
        }
        Long deleted = redisTemplate.delete(keys);
        return deleted == null ? 0 : deleted.intValue();
    }

    public void addToBlacklist(String jti, Duration ttl) {
        // 이미 만료된 토큰은 필터에서 걸러지므로 저장할 필요가 없다.
        if (ttl.isZero() || ttl.isNegative()) {
            return;
        }
        redisTemplate.opsForValue().set(BLACKLIST_KEY_PREFIX + jti, BLACKLIST_VALUE, ttl);
    }

    public boolean isBlacklisted(String jti) {
        return Boolean.TRUE.equals(redisTemplate.hasKey(BLACKLIST_KEY_PREFIX + jti));
    }

    /**
     * 탈퇴한 회원 표시를 남긴다. TTL은 이미 발급된 access 토큰이 모두 만료되는 시간(access 최대 수명)이면 충분하다.
     */
    public void markWithdrawn(UUID userId, Duration ttl) {
        redisTemplate.opsForValue().set(WITHDRAWN_KEY_PREFIX + userId, WITHDRAWN_VALUE, ttl);
    }

    /**
     * access 토큰이 더 이상 쓸 수 없는 상태인지(블랙리스트 또는 탈퇴한 회원) Redis 한 번 호출로 확인한다.
     */
    public boolean isRevoked(String jti, UUID userId) {
        Long existing = redisTemplate.countExistingKeys(List.of(BLACKLIST_KEY_PREFIX + jti, WITHDRAWN_KEY_PREFIX + userId));
        return existing != null && existing > 0;
    }

    private String refreshKey(UUID userId, String jti) {
        return REFRESH_KEY_PREFIX + userId + ":" + jti;
    }
}
