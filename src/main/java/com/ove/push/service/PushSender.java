package com.ove.push.service;

/**
 * 앱 푸시 발송 어댑터. FCM/APNs 같은 실제 제공자를 붙일 때 이 인터페이스를 구현한다.
 */
public interface PushSender {

    void send(PushMessage message);
}
