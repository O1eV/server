package com.ove.realtime.config;

import java.security.Principal;

import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessagingException;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.stereotype.Component;

import com.ove.auth.repository.TokenRedisRepository;
import com.ove.global.security.JwtProvider;
import com.ove.global.security.TokenType;
import com.ove.market.service.MarketService;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class StompAuthInterceptor implements ChannelInterceptor {

    private static final String BEARER_PREFIX = "Bearer ";
    private static final String PRICES_PREFIX = "/topic/prices/";
    private static final String SIGNALS_PREFIX = "/topic/signals/";
    private static final String ALERTS_DESTINATION = "/user/queue/alerts";

    private final JwtProvider jwtProvider;
    private final TokenRedisRepository tokenRedisRepository;
    private final MarketService marketService;

    // 접속(CONNECT) 때는 토큰을, 구독(SUBSCRIBE) 때는 들어가도 되는 주소인지를 확인할 때 사용
    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (accessor == null) {
            return message;
        }
        if (StompCommand.CONNECT.equals(accessor.getCommand())) {
            String authorization = accessor.getFirstNativeHeader("Authorization");
            if (authorization != null) {
                accessor.setUser(authenticate(authorization));
            }
        }
        if (StompCommand.SUBSCRIBE.equals(accessor.getCommand())) {
            checkSubscribe(accessor.getDestination(), accessor.getUser());
        }
        return message;
    }

    // Authorization 헤더의 access 토큰으로 접속한 사용자를 정할 때 사용
    private Principal authenticate(String authorization) {
        if (!authorization.startsWith(BEARER_PREFIX)) {
            throw new MessagingException("UNAUTHORIZED");
        }
        try {
            Claims claims = jwtProvider.parse(authorization.substring(BEARER_PREFIX.length()).trim());
            if (jwtProvider.getTokenType(claims) != TokenType.ACCESS
                    || tokenRedisRepository.isBlacklisted(claims.getId())) {
                throw new MessagingException("UNAUTHORIZED");
            }
            return new StompPrincipal(claims.getSubject());
        } catch (ExpiredJwtException e) {
            throw new MessagingException("TOKEN_EXPIRED");
        } catch (JwtException | IllegalArgumentException e) {
            throw new MessagingException("UNAUTHORIZED");
        }
    }

    // 구독 주소마다 로그인이 필요한지, 제공하는 종목인지 확인할 때 사용
    private void checkSubscribe(String destination, Principal user) {
        if (destination == null) {
            throw new MessagingException("INVALID_REQUEST");
        }
        if (destination.startsWith(PRICES_PREFIX)) {
            checkSymbol(destination.substring(PRICES_PREFIX.length()));
        } else if (destination.startsWith(SIGNALS_PREFIX)) {
            checkLogin(user);
            checkSymbol(destination.substring(SIGNALS_PREFIX.length()));
        } else if (destination.equals(ALERTS_DESTINATION)) {
            checkLogin(user);
        } else {
            throw new MessagingException("INVALID_REQUEST");
        }
    }

    // 로그인하지 않은 접속이면 막을 때 사용
    private void checkLogin(Principal user) {
        if (user == null) {
            throw new MessagingException("UNAUTHORIZED");
        }
    }

    // 제공하지 않는 종목이면 막을 때 사용
    private void checkSymbol(String symbol) {
        if (!marketService.isSupported(symbol)) {
            throw new MessagingException("MARKET_NOT_FOUND");
        }
    }
}
