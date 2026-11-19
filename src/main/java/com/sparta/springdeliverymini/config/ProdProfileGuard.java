package com.sparta.springdeliverymini.config;

import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.Environment;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;

// prod 프로필 기동 전 점검 (#54, docs/operations.md)
// - 필수 환경변수가 비어 있으면 기동 실패: DB·JWT는 원래 기동 중에 실패하지만, Redis·Kafka는 처음 쓸 때 접속하므로
//   주소가 빠져도 서버가 떠서 결제(멱등성)·이벤트 발행이 뒤늦게 실패함 → 배포 시점에 바로 드러나도록
// - CORS 허용 출처(CORS_ALLOWED_ORIGINS)가 비어 있으면 기동 실패: 빈 목록이어도 서버는 정상 기동하고 브라우저 요청만 모두 403이 되어
//   배포 후 프론트엔드에서야 드러나므로 (와일드카드·형식 오류는 CorsProperties 바인딩에서 막음)
// - API 문서(springdoc)가 켜져 있으면 기동 실패: 환경변수(SPRINGDOC_*)로 잘못 켜도 운영에 API 구조가 공개되지 않도록
// - Flyway 자동 baseline이 켜져 있으면 기동 실패 (application-prod.yml)
// 오류 메시지에는 변수 이름만 넣고 값은 넣지 않음 (로그에 비밀 값이 남지 않도록)
// 빈이 아니라 EnvironmentPostProcessor(META-INF/spring.factories 등록)로 검사 (#61):
// 빈으로 두면 생성 순서가 보장되지 않아 DB_URL이 없을 때 DataSource가 먼저 실패하고 빠진 변수 목록이 나오지 않음
// → 설정 파일·프로필이 모두 반영된 직후, 어떤 빈도 만들기 전에 검사
public class

ProdProfileGuard implements EnvironmentPostProcessor, Ordered {

    static final List<String> REQUIRED_ENV = List.of(
            "DB_URL", "DB_USERNAME", "DB_PASSWORD", "JWT_SECRET", "REDIS_HOST", "KAFKA_BOOTSTRAP_SERVERS",
            "CORS_ALLOWED_ORIGINS");

    // spring.flyway.baseline-on-migrate: 잘못된 DB(이력 테이블 없이 테이블만 있는 DB)에 붙었을 때 V1을 적용 완료로 기록하고 진행하지 않도록
    static final List<String> MUST_BE_DISABLED = List.of(
            "springdoc.api-docs.enabled", "springdoc.swagger-ui.enabled", "spring.flyway.baseline-on-migrate");

    // ConfigDataEnvironmentPostProcessor(application-*.yml·활성 프로필 반영) 뒤에 실행되도록 가장 늦은 순서
    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        if (environment.matchesProfiles("prod")) {
            check(environment);
        }
    }

    static void check(Environment environment) {
        List<String> problems = new ArrayList<>();

        List<String> missing = REQUIRED_ENV.stream()
                .filter(name -> !StringUtils.hasText(environment.getProperty(name)))
                .toList();
        if (!missing.isEmpty()) {
            problems.add("필수 환경변수가 없습니다: " + String.join(", ", missing));
        }

        List<String> enabled = MUST_BE_DISABLED.stream()
                .filter(key -> environment.getProperty(key, Boolean.class, false))
                .toList();
        if (!enabled.isEmpty()) {
            problems.add("운영 프로필에서는 비활성이어야 합니다: " + String.join(", ", enabled));
        }

        if (!problems.isEmpty()) {
            throw new IllegalStateException("prod 프로필 설정 오류 — " + String.join(" / ", problems));
        }
    }
}
