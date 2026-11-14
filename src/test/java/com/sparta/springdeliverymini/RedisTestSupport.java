package com.sparta.springdeliverymini;

import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

import java.io.IOException;
import java.net.ServerSocket;
import java.time.Duration;

// Spring 컨텍스트 없이 실제 Redis가 필요한 테스트용 (분산 락·Redis 장애 테스트)
//
// - TestcontainersConfiguration의 Redis와 분리한 이유: CLIENT PAUSE로 Redis를 멈추는 장애 테스트가
//   Spring 통합 테스트들이 함께 쓰는 Redis에 영향을 주지 않도록
// - 컨테이너는 처음 사용할 때 한 번만 띄우고 JVM 종료 시 Testcontainers가 정리
// - 테스트는 Gradle 기본 설정(한 JVM에서 순서대로)으로 실행되므로 이 클래스를 쓰는 테스트끼리 동시에 돌지 않음
public final class RedisTestSupport {

    private static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:8.2")).withExposedPorts(6379);

    private RedisTestSupport() {
    }

    private static synchronized GenericContainer<?> redis() {
        if (!REDIS.isRunning()) {
            REDIS.start();
        }
        return REDIS;
    }

    // 명령 타임아웃을 지정한 클라이언트 — 운영 설정(spring.data.redis.timeout)처럼 짧게 두고 타임아웃을 재현
    // 호출마다 새 연결 팩토리 → "서로 다른 애플리케이션 인스턴스"를 흉내 낼 때도 사용
    public static StringRedisTemplate template(Duration commandTimeout) {
        return template(redis().getHost(), redis().getMappedPort(6379), commandTimeout);
    }

    // 아무것도 듣고 있지 않은 포트 — 연결 실패(Redis 다운) 재현
    public static StringRedisTemplate unreachableTemplate() {
        int port;
        try (ServerSocket socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        return template("localhost", port, Duration.ofMillis(500));
    }

    // 모든 클라이언트의 명령을 잠시 멈춤 → 그동안 보낸 명령은 클라이언트 타임아웃 (Redis 지연·타임아웃 재현)
    // 멈춘 동안 보낸 명령은 해제 뒤 Redis에서 실행됨 (실제 타임아웃과 같은 상황)
    public static void pause(Duration duration) {
        try {
            var result = redis().execInContainer("redis-cli", "CLIENT", "PAUSE", String.valueOf(duration.toMillis()), "ALL");
            if (result.getExitCode() != 0) {
                throw new IllegalStateException("CLIENT PAUSE 실패: " + result.getStderr());
            }
        } catch (IOException | InterruptedException e) {
            throw new IllegalStateException(e);
        }
    }

    // 멈춤이 풀릴 때까지 기다리고 모든 키 삭제 — 다음 테스트에 영향이 없도록 @AfterEach에서 호출
    public static void resetAfterPause() {
        StringRedisTemplate admin = template(Duration.ofSeconds(30)); // 멈춘 동안에는 PING이 기다림
        admin.execute(connection -> {
            connection.serverCommands().flushAll();
            return null;
        }, true);
    }

    private static StringRedisTemplate template(String host, int port, Duration commandTimeout) {
        LettuceClientConfiguration clientConfig = LettuceClientConfiguration.builder()
                .commandTimeout(commandTimeout)
                .build();
        LettuceConnectionFactory factory = new LettuceConnectionFactory(new RedisStandaloneConfiguration(host, port), clientConfig);
        factory.afterPropertiesSet();
        factory.start();
        return new StringRedisTemplate(factory);
    }
}
