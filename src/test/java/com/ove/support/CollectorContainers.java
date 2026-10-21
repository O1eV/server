package com.ove.support;

import org.testcontainers.containers.GenericContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * 수집기 테스트용 컨테이너. JVM 전체에서 한 번만 띄워 여러 테스트 클래스가 같이 쓴다(Docker 필요).
 * TimescaleDB는 docker-compose.yml과 같은 이미지를 쓴다.
 */
public final class CollectorContainers {

    public static final PostgreSQLContainer TIMESCALE = new PostgreSQLContainer(
            DockerImageName.parse("timescale/timescaledb:2.17.2-pg16").asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("ove_test")
            .withUsername("ove")
            .withPassword("ove");

    public static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7.4-alpine")
            .withExposedPorts(6379);

    static {
        TIMESCALE.start();
        REDIS.start();
    }

    private CollectorContainers() {
    }

    public static String redisHost() {
        return REDIS.getHost();
    }

    public static int redisPort() {
        return REDIS.getMappedPort(6379);
    }
}
