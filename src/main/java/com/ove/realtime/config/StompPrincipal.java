package com.ove.realtime.config;

import java.security.Principal;

public record StompPrincipal(String userId) implements Principal {

    // 개인 알림(/user/queue/alerts)을 보낼 때 사용자를 이 이름(userId)으로 찾을 때 사용
    @Override
    public String getName() {
        return userId;
    }
}
