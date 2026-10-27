package com.ove.support;

import org.junit.jupiter.api.AfterEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;

import com.ove.account.repository.PaperAccountRepository;
import com.ove.paper.repository.PaperHoldingRepository;
import com.ove.paper.repository.PaperOrderRepository;
import com.ove.user.repository.TermsAgreementRepository;
import com.ove.user.repository.UserRepository;
import com.ove.watchlist.repository.WatchlistRepository;

import tools.jackson.databind.ObjectMapper;

/**
 * 통합 테스트 공통 설정.
 * DB는 기존 테스트 설정대로 인메모리 H2를 쓰고, Redis는 embedded 대안이 마땅치 않아
 * Testcontainers로 실제 Redis를 띄운다(Docker 필요).
 *
 * 테스트 클래스끼리 Spring 컨텍스트를 캐시해 공유하므로, 컨테이너도 클래스마다 내렸다 올리지 않고
 * JVM 전체에서 한 번만 띄운다(@Container를 쓰면 첫 클래스가 끝날 때 컨테이너가 내려가 포트가 바뀐다).
 */
@SpringBootTest
@AutoConfigureMockMvc
public abstract class IntegrationTestSupport {

    @ServiceConnection(name = "redis")
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7.4-alpine").withExposedPorts(6379);

    static {
        REDIS.start();
    }

    @Autowired
    protected MockMvc mockMvc;

    @Autowired
    protected ObjectMapper objectMapper;

    @Autowired
    protected StringRedisTemplate redisTemplate;

    @Autowired
    protected UserRepository userRepository;

    @Autowired
    protected TermsAgreementRepository termsAgreementRepository;

    @Autowired
    protected PaperAccountRepository paperAccountRepository;

    @Autowired
    protected PaperHoldingRepository paperHoldingRepository;

    @Autowired
    protected PaperOrderRepository paperOrderRepository;

    @Autowired
    protected WatchlistRepository watchlistRepository;

    @AfterEach
    void cleanUp() {
        watchlistRepository.deleteAllInBatch();
        paperOrderRepository.deleteAllInBatch();
        paperHoldingRepository.deleteAllInBatch();
        paperAccountRepository.deleteAllInBatch();
        termsAgreementRepository.deleteAllInBatch();
        userRepository.deleteAllInBatch();
        redisTemplate.getConnectionFactory().getConnection().serverCommands().flushAll();
    }

    protected String toJson(Object value) {
        return objectMapper.writeValueAsString(value);
    }
}
