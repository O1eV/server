package com.ove.realtime.config;

import java.security.Principal;
import java.util.EnumSet;
import java.util.Set;

import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessagingException;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.stereotype.Component;

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
    private static final Set<StompCommand> ALLOWED_COMMANDS = EnumSet.of(
            StompCommand.CONNECT, StompCommand.STOMP, StompCommand.SUBSCRIBE,
            StompCommand.UNSUBSCRIBE, StompCommand.DISCONNECT);

    private final JwtProvider jwtProvider;
    private final StompTokenGuard tokenGuard;

    // 클라이언트가 보낸 프레임을 검사할 때 사용 (SEND 같은 허용 안 한 프레임은 막고, 접속 때 토큰·구독 때 주소를 확인)
    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (accessor == null || accessor.getCommand() == null) {
            return message;
        }
        StompCommand command = accessor.getCommand();
        if (!ALLOWED_COMMANDS.contains(command)) {
            throw new MessagingException("FORBIDDEN");
        }
        if (command == StompCommand.CONNECT || command == StompCommand.STOMP) {
            String authorization = accessor.getFirstNativeHeader("Authorization");
            if (authorization != null) {
                StompPrincipal principal = authenticate(authorization);
                accessor.setUser(principal);
                tokenGuard.remember(accessor.getSessionId(), principal);
            }
        }
        if (command == StompCommand.SUBSCRIBE) {
            checkSubscribe(accessor.getDestination(), accessor.getUser());
        }
        if (command == StompCommand.DISCONNECT) {
            tokenGuard.forget(accessor.getSessionId());
        }
        return message;
    }

    // Authorization 헤더의 access 토큰으로 접속한 사용자를 정할 때 사용
    private StompPrincipal authenticate(String authorization) {
        if (!authorization.startsWith(BEARER_PREFIX)) {
            throw new MessagingException("UNAUTHORIZED");
        }
        Claims claims;
        try {
            claims = jwtProvider.parse(authorization.substring(BEARER_PREFIX.length()).trim());
        } catch (ExpiredJwtException e) {
            throw new MessagingException("TOKEN_EXPIRED");
        } catch (JwtException | IllegalArgumentException e) {
            throw new MessagingException("UNAUTHORIZED");
        }
        if (jwtProvider.getTokenType(claims) != TokenType.ACCESS || claims.getSubject() == null) {
            throw new MessagingException("UNAUTHORIZED");
        }
        StompPrincipal principal = new StompPrincipal(claims.getSubject(), claims.getId(),
                claims.getExpiration().toInstant());
        checkToken(principal);
        return principal;
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

    // 로그인하지 않았거나, 접속 뒤 토큰이 만료·로그아웃됐으면 막을 때 사용
    private void checkLogin(Principal user) {
        if (!(user instanceof StompPrincipal principal)) {
            throw new MessagingException("UNAUTHORIZED");
        }
        checkToken(principal);
    }

    // 토큰이 아직 쓸 수 있는지 StompTokenGuard로 확인할 때 사용
    private void checkToken(StompPrincipal principal) {
        String error = tokenGuard.check(principal);
        if (error != null) {
            throw new MessagingException(error);
        }
    }

    // 제공하지 않는 종목이면 막을 때 사용
    private void checkSymbol(String symbol) {
        try {
            MarketService.requireSupportedSymbol(symbol);
        } catch (com.ove.global.exception.BusinessException error) {
            throw new MessagingException("MARKET_NOT_FOUND");
        }
    }
}
