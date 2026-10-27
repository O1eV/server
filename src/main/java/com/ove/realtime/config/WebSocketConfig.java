package com.ove.realtime.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

import com.ove.global.security.CorsProperties;

import lombok.RequiredArgsConstructor;

@Configuration
@EnableWebSocketMessageBroker
@RequiredArgsConstructor
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    private final StompAuthInterceptor stompAuthInterceptor;
    private final StompTokenGuard stompTokenGuard;
    private final CorsProperties corsProperties;

    // 프론트가 접속할 WebSocket 주소(/ws)를 열 때 사용 (허용 origin은 CORS 설정과 같게)
    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws")
                .setAllowedOrigins(corsProperties.allowedOrigins().toArray(new String[0]));
    }

    // 서버가 메시지를 보내는 주소(/topic, /queue)와 개인 알림 주소(/user)를 정할 때 사용
    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        registry.enableSimpleBroker("/topic", "/queue")
                .setTaskScheduler(heartbeatScheduler()).setHeartbeatValue(new long[] {10000, 10000});
        registry.setUserDestinationPrefix("/user");
    }

    @org.springframework.context.annotation.Bean
    public org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler heartbeatScheduler() {
        var scheduler = new org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("stomp-heartbeat-");
        return scheduler;
    }

    // 접속·구독 요청이 들어오면 로그인 검사기를 먼저 거치게 할 때 사용
    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(stompAuthInterceptor);
    }

    // 서버가 보내는 신호·알림을 토큰이 만료·로그아웃된 세션에는 안 보내게 할 때 사용
    @Override
    public void configureClientOutboundChannel(ChannelRegistration registration) {
        registration.interceptors(stompTokenGuard);
    }
}
