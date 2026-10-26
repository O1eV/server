package com.ove.push.service;

import java.util.Map;

/**
 * 푸시 발송 어댑터에 넘기는 메시지. 토큰은 로그에 남기지 않도록 deviceId와 분리해 둔다.
 */
public record PushMessage(
        Long deviceId,
        String token,
        String title,
        String body,
        Map<String, String> data) {
}
