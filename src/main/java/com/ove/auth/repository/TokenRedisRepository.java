package com.ove.auth.repository;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

import lombok.RequiredArgsConstructor;

/**
 * Redis에 refresh 토큰과 access 토큰 블랙리스트를 저장한다.
 *
 * - refresh:{userId}:{jti} = refresh 토큰의 SHA-256 해시 (TTL = refresh 만료까지)
 *   기기마다 jti가 달라 다중 기기 로그인을 허용한다.
 * - blacklist:{jti} = 로그아웃된 access 토큰 (TTL = access 남은 만료 시간)
 */
@Repository
@RequiredArgsConstructor
public class TokenRedisRepository {

    private static final String REFRESH_KEY_PREFIX = "refresh:";
    private static final String BLACKLIST_KEY_PREFIX = "blacklist:";
    private static final String BLACKLIST_VALUE = "logout";

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

    private String refreshKey(UUID userId, String jti) {
        return REFRESH_KEY_PREFIX + userId + ":" + jti;
    }
}
