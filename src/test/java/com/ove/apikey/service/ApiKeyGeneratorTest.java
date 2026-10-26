package com.ove.apikey.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ApiKeyGeneratorTest {

    private final ApiKeyGenerator generator = new ApiKeyGenerator();

    @Test
    @DisplayName("키 원문은 ove_ + hex 64자이고 매번 다르다")
    void format() {
        Set<String> keys = new HashSet<>();
        for (int i = 0; i < 100; i++) {
            String key = generator.generate();
            assertThat(key).matches("ove_[0-9a-f]{64}");
            keys.add(key);
        }
        assertThat(keys).hasSize(100);
    }

    @Test
    @DisplayName("prefix는 원문의 앞 8자리, 해시는 SHA-256 hex 64자이고 같은 입력이면 같다")
    void prefixAndHash() {
        String key = "ove_0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

        assertThat(generator.prefixOf(key)).isEqualTo("ove_0123");
        assertThat(generator.hash(key)).matches("[0-9a-f]{64}").isEqualTo(generator.hash(key)).isNotEqualTo(key);
        // "abc"의 SHA-256 알려진 값
        assertThat(generator.hash("abc")).isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
    }
}
