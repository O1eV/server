package com.ove.apikey.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.HexFormat;

import org.springframework.stereotype.Component;

/**
 * API 키 원문을 만들고 해시를 계산한다. 원문은 "ove_" + 랜덤 32바이트의 hex(64자)다.
 */
@Component
public class ApiKeyGenerator {

    public static final String KEY_PREFIX = "ove_";
    private static final int RANDOM_BYTES = 32;
    private static final int PREFIX_LENGTH = 8;

    private final SecureRandom random = new SecureRandom();

    /** 새 키 원문. 예측할 수 없도록 SecureRandom을 쓴다. */
    public String generate() {
        byte[] bytes = new byte[RANDOM_BYTES];
        random.nextBytes(bytes);
        return KEY_PREFIX + HexFormat.of().formatHex(bytes);
    }

    /** 목록에서 키를 알아보기 위한 앞 8자리("ove_" + 4자). */
    public String prefixOf(String rawKey) {
        return rawKey.substring(0, PREFIX_LENGTH);
    }

    /** 저장·조회에 쓰는 SHA-256(hex 64자). 키는 이미 충분히 긴 무작위 값이라 솔트 없는 해시로 충분하다. */
    public String hash(String rawKey) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(rawKey.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256을 사용할 수 없습니다.", e);
        }
    }
}
