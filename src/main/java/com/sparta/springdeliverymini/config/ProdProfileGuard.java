package com.sparta.springdeliverymini.config;

import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;

// prod 프로필 기동 전 점검 (#54, docs/operations.md)
// - 필수 환경변수가 비어 있으면 기동 실패: DB·JWT는 원래 기동 중에 실패하지만, Redis·Kafka는 처음 쓸 때 접속하므로
//   주소가 빠져도 서버가 떠서 결제(멱등성)·이벤트 발행이 뒤늦게 실패함 → 배포 시점에 바로 드러나도록
// - API 문서(springdoc)가 켜져 있으면 기동 실패: 환경변수(SPRINGDOC_*)로 잘못 켜도 운영에 API 구조가 공개되지 않도록
// - Flyway 자동 baseline이 켜져 있으면 기동 실패 (application-prod.yml)
// 오류 메시지에는 변수 이름만 넣고 값은 넣지 않음 (로그에 비밀 값이 남지 않도록)
@Component
@Profile("prod")
public class ProdProfileGuard {

    static final List<String> REQUIRED_ENV = List.of(
            "DB_URL", "DB_USERNAME", "DB_PASSWORD", "JWT_SECRET", "REDIS_HOST", "KAFKA_BOOTSTRAP_SERVERS");

    // spring.flyway.baseline-on-migrate: 잘못된 DB(이력 테이블 없이 테이블만 있는 DB)에 붙었을 때 V1을 적용 완료로 기록하고 진행하지 않도록
    static final List<String> MUST_BE_DISABLED = List.of(
            "springdoc.api-docs.enabled", "springdoc.swagger-ui.enabled", "spring.flyway.baseline-on-migrate");

    public ProdProfileGuard(Environment environment) {
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
