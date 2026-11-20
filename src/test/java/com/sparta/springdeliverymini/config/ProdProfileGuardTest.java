package com.sparta.springdeliverymini.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// prod 프로필 기동 점검 (#54) — 필수 환경변수 누락·API 문서 활성화 시 기동 실패
class ProdProfileGuardTest {

    private MockEnvironment completeEnvironment() {
        MockEnvironment environment = new MockEnvironment();
        ProdProfileGuard.REQUIRED_ENV.forEach(name -> environment.setProperty(name, "secret-value-" + name));
        environment.setProperty("springdoc.api-docs.enabled", "false");
        environment.setProperty("springdoc.swagger-ui.enabled", "false");
        environment.setProperty("spring.flyway.baseline-on-migrate", "false");
        return environment;
    }

    @Test
    void 필수_환경변수가_모두_있고_API_문서가_꺼져_있으면_통과한다() {
        assertThatCode(() -> ProdProfileGuard.check(completeEnvironment())).doesNotThrowAnyException();
    }

    @Test
    void 필수_환경변수가_없거나_빈_값이면_이름만_알려주고_기동을_막는다() {
        MockEnvironment environment = new MockEnvironment();
        environment.setProperty("DB_URL", "jdbc:postgresql://db/delivery");
        environment.setProperty("DB_USERNAME", "delivery");
        environment.setProperty("DB_PASSWORD", "db-password-value");
        environment.setProperty("JWT_SECRET", "jwt-secret-value");
        environment.setProperty("REDIS_HOST", "  "); // 공백만 있는 값도 없는 것으로 봄
        // KAFKA_BOOTSTRAP_SERVERS 없음

        assertThatThrownBy(() -> ProdProfileGuard.check(environment))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("REDIS_HOST, KAFKA_BOOTSTRAP_SERVERS")
                // 설정된 비밀 값은 메시지에 들어가지 않음
                .hasMessageNotContaining("db-password-value")
                .hasMessageNotContaining("jwt-secret-value");
    }

    // CORS 허용 출처가 비어 있으면 서버는 뜨지만 브라우저 요청이 모두 403 → 배포 시점에 막음 (#61)
    @Test
    void CORS_허용_출처가_없으면_기동을_막는다() {
        MockEnvironment environment = completeEnvironment();
        environment.setProperty("CORS_ALLOWED_ORIGINS", "");

        assertThatThrownBy(() -> ProdProfileGuard.check(environment))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("필수 환경변수가 없습니다: CORS_ALLOWED_ORIGINS");
    }

    @Test
    void API_문서가_켜져_있으면_기동을_막는다() {
        MockEnvironment environment = completeEnvironment();
        environment.setProperty("springdoc.swagger-ui.enabled", "true");

        assertThatThrownBy(() -> ProdProfileGuard.check(environment))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("springdoc.swagger-ui.enabled")
                .hasMessageNotContaining("springdoc.api-docs.enabled");
    }

    // application-prod.yml이 끄지만, 환경변수(SPRING_FLYWAY_BASELINE_ON_MIGRATE)로 다시 켠 경우
    @Test
    void Flyway_자동_baseline이_켜져_있으면_기동을_막는다() {
        MockEnvironment environment = completeEnvironment();
        environment.setProperty("spring.flyway.baseline-on-migrate", "true");

        assertThatThrownBy(() -> ProdProfileGuard.check(environment))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("spring.flyway.baseline-on-migrate");
    }

    // 발송 채널이 slack인데 URL이 없으면 모든 알림이 FAILED로 쌓이기만 함 → 배포 시점에 막음 (#69)
    @Test
    void 알림_발송기가_slack인데_웹훅_URL이_없으면_기동을_막는다() {
        MockEnvironment environment = completeEnvironment();
        environment.setProperty("notification.sender", "Slack"); // 대소문자 무시

        assertThatThrownBy(() -> ProdProfileGuard.check(environment))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("필수 환경변수가 없습니다: SLACK_WEBHOOK_URL");
    }

    @Test
    void 알림_발송기가_slack이고_웹훅_URL이_있으면_통과하고_log면_URL이_없어도_통과한다() {
        MockEnvironment slack = completeEnvironment();
        slack.setProperty("notification.sender", "slack");
        slack.setProperty("SLACK_WEBHOOK_URL", "https://hooks.slack.com/services/secret");
        assertThatCode(() -> ProdProfileGuard.check(slack)).doesNotThrowAnyException();

        MockEnvironment log = completeEnvironment();
        log.setProperty("notification.sender", "log");
        assertThatCode(() -> ProdProfileGuard.check(log)).doesNotThrowAnyException();
    }
}
