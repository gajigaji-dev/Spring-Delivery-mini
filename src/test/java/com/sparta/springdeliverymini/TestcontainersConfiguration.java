package com.sparta.springdeliverymini;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

// 통합 테스트용 PostgreSQL 컨테이너 설정
// @SpringBootTest 클래스에 @Import(TestcontainersConfiguration.class)를 붙여 사용
//
// - @ServiceConnection: 컨테이너의 접속 정보(URL·계정)를 spring.datasource.* 대신 자동으로 연결
//   → 로컬 DB나 DB_USERNAME/DB_PASSWORD 환경변수 없이 테스트 실행 가능 (Docker만 필요)
// - 컨테이너는 Spring Bean이므로, 같은 설정을 쓰는 테스트 클래스끼리는 Spring의 테스트 컨텍스트 캐시로
//   한 번 띄운 컨테이너를 함께 사용하고, 테스트 실행이 끝나면 정리됨
// - 스키마는 애플리케이션과 똑같이 Flyway(db/migration)가 생성 → 마이그레이션 SQL도 함께 검증됨
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

    // 운영과 같은 PostgreSQL 18 사용 (README 기술 스택 기준)
    @Bean
    @ServiceConnection
    PostgreSQLContainer postgresContainer() {
        return new PostgreSQLContainer(DockerImageName.parse("postgres:18"));
    }

    // 결제 멱등성·스케줄러 분산 락용 Redis (docker-compose.yml과 같은 버전)
    // 전용 모듈 없이 GenericContainer + @ServiceConnection(name = "redis")로 spring.data.redis.* 자동 연결
    @Bean
    @ServiceConnection(name = "redis")
    GenericContainer<?> redisContainer() {
        return new GenericContainer<>(DockerImageName.parse("redis:8.2")).withExposedPorts(6379);
    }
}
