package com.ove.realtime.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.PatternTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

import com.ove.market.collector.CollectorKeys;
import com.ove.realtime.service.PriceRelay;

@Configuration
@ConditionalOnProperty(prefix = "collector", name = "enabled", havingValue = "true")
public class PriceRelayConfig {

    // 시세 수집기가 켜져 있을 때 Redis의 prices:* 채널을 듣고 PriceRelay로 넘길 때 사용
    @Bean
    public RedisMessageListenerContainer priceRelayContainer(RedisConnectionFactory connectionFactory,
                                                             PriceRelay priceRelay) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        container.addMessageListener(priceRelay, new PatternTopic(CollectorKeys.tickChannel("*")));
        return container;
    }
}
