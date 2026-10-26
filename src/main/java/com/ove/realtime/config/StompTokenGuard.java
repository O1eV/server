package com.ove.realtime.config;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.stereotype.Component;

import com.ove.auth.repository.TokenRedisRepository;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class StompTokenGuard implements ChannelInterceptor {

    private final TokenRedisRepository tokenRedisRepository;
    private final Map<String, StompPrincipal> sessions = new ConcurrentHashMap<>();

    // 로그인해서 접속한 세션의 토큰 정보를 기억할 때 사용
    public void remember(String sessionId, StompPrincipal principal) {
        sessions.put(sessionId, principal);
    }

    // 접속이 끊긴 세션을 잊을 때 사용
    public void forget(String sessionId) {
        sessions.remove(sessionId);
    }

    // 토큰이 만료됐으면 TOKEN_EXPIRED, 로그아웃(블랙리스트)됐거나 탈퇴한 회원이면 UNAUTHORIZED, 괜찮으면 null을 돌려줄 때 사용
    // REST의 JwtAuthenticationFilter와 같은 isRevoked(jti, userId)로 확인해서 두 경로의 판단이 어긋나지 않게 한다.
    public String check(StompPrincipal principal) {
        if (!Instant.now().isBefore(principal.accessTokenExpiresAt())) {
            return "TOKEN_EXPIRED";
        }
        UUID userId;
        try {
            userId = UUID.fromString(principal.userId());
        } catch (IllegalArgumentException e) {
            return "UNAUTHORIZED";
        }
        if (tokenRedisRepository.isRevoked(principal.accessTokenJti(), userId)) {
            return "UNAUTHORIZED";
        }
        return null;
    }

    // 서버가 보내는 신호·알림을, 토큰이 만료·로그아웃·탈퇴된 세션에는 보내지 않을 때 사용 (clientOutboundChannel)
    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        if (SimpMessageHeaderAccessor.getMessageType(message.getHeaders()) != SimpMessageType.MESSAGE
                || !isLoginOnly(message)) {
            return message;
        }
        StompPrincipal principal = sessions.get(SimpMessageHeaderAccessor.getSessionId(message.getHeaders()));
        if (principal == null || check(principal) != null) {
            return null;
        }
        return message;
    }

    // 로그인해야 받을 수 있는 주소(신호, 내 알림)로 가는 메시지인지 확인할 때 사용
    private boolean isLoginOnly(Message<?> message) {
        String destination = SimpMessageHeaderAccessor.getDestination(message.getHeaders());
        Object original = message.getHeaders().get(SimpMessageHeaderAccessor.ORIGINAL_DESTINATION);
        return (destination != null && (destination.startsWith("/topic/signals/") || destination.startsWith("/queue/alerts")))
                || "/user/queue/alerts".equals(original);
    }
}
