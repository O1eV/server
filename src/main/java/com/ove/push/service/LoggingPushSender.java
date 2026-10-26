package com.ove.push.service;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;

/**
 * 푸시 제공자가 아직 정해지지 않은 동안 쓰는 기본 발송기.
 * 실제 발송 대신 대상 기기와 메시지를 로그로 남겨, 신호 전환·관심 종목·알림 설정이 맞물리는지 확인할 수 있게 한다.
 * push.sender=fcm 등으로 바꾸면 이 빈 대신 다른 PushSender 구현을 쓸 수 있다.
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "push", name = "sender", havingValue = "logging", matchIfMissing = true)
public class LoggingPushSender implements PushSender {

    @Override
    public void send(PushMessage message) {
        // 토큰 원문은 개인정보·인증 정보에 준하므로 로그에 남기지 않는다.
        log.info("푸시 발송(로그 모드): deviceId={}, title={}, body={}",
                message.deviceId(), message.title(), message.body());
    }
}
