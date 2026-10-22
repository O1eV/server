package com.ove.support;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.PatternTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

/**
 * 테스트용 Redis 연결 + Pub/Sub 메시지 기록기. prices:*, candles.closed:* 채널의 메시지를 모은다.
 */
public class RedisRecorder implements AutoCloseable {

    /** {channel, message} 순서대로 받은 메시지 */
    private final List<String[]> messages = new CopyOnWriteArrayList<>();
    private final LettuceConnectionFactory connectionFactory;
    private final StringRedisTemplate redisTemplate;
    private final RedisMessageListenerContainer listenerContainer;

    public RedisRecorder(String host, int port) {
        connectionFactory = new LettuceConnectionFactory(host, port);
        connectionFactory.afterPropertiesSet();
        redisTemplate = new StringRedisTemplate(connectionFactory);
        redisTemplate.afterPropertiesSet();

        listenerContainer = new RedisMessageListenerContainer();
        listenerContainer.setConnectionFactory(connectionFactory);
        listenerContainer.afterPropertiesSet();
        listenerContainer.start();
        listenerContainer.addMessageListener((message, pattern) -> messages.add(
                new String[] {new String(message.getChannel()), new String(message.getBody())}),
                List.of(new PatternTopic("prices:*"), new PatternTopic("candles.closed:*")));
    }

    public StringRedisTemplate template() {
        return redisTemplate;
    }

    public List<String[]> messages() {
        return messages;
    }

    public List<String> messagesOn(String channel) {
        return messages.stream().filter(message -> message[0].equals(channel)).map(message -> message[1]).toList();
    }

    public void reset() {
        redisTemplate.getConnectionFactory().getConnection().serverCommands().flushAll();
        messages.clear();
    }

    @Override
    public void close() throws Exception {
        listenerContainer.stop();
        listenerContainer.destroy();
        connectionFactory.destroy();
    }
}
