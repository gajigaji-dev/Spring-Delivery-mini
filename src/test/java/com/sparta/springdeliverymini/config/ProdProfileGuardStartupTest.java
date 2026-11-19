package com.sparta.springdeliverymini.config;

import com.sparta.springdeliverymini.SpringDeliveryMiniApplication;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// ProdProfileGuard가 빈 생성 전(환경 준비 단계)에 실행되는지 확인 (#61)
// 빈으로 등록했을 때는 DB_URL이 없으면 DataSource가 먼저 실패해 빠진 변수 목록이 나오지 않았음
// → DB(Testcontainers) 없이 띄워도 DataSource 오류가 아니라 가드 메시지로 실패해야 함
class ProdProfileGuardStartupTest {

    private SpringApplication application() {
        SpringApplication application = new SpringApplication(SpringDeliveryMiniApplication.class);
        application.setWebApplicationType(WebApplicationType.NONE);
        return application;
    }

    @Test
    void DB_URL이_없으면_DataSource보다_먼저_빠진_변수_목록으로_기동이_실패한다() {
        // 명령행 인자는 설정 파일보다 우선 → 빈 값이 그대로 적용됨
        assertThatThrownBy(() -> application().run(
                "--spring.profiles.active=test,prod",
                "--DB_URL=",
                "--DB_USERNAME=guard-check-only",
                "--DB_PASSWORD=guard-check-only",
                "--JWT_SECRET=guard-check-only",
                "--REDIS_HOST=localhost",
                "--KAFKA_BOOTSTRAP_SERVERS=",
                "--CORS_ALLOWED_ORIGINS=https://app.example.com"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("필수 환경변수가 없습니다: DB_URL, KAFKA_BOOTSTRAP_SERVERS")
                .hasMessageNotContaining("guard-check-only");
    }

    @Test
    void prod가_아닌_프로필에서는_검사하지_않는다() {
        MockEnvironment environment = new MockEnvironment(); // 필수 값 하나도 없음
        environment.setActiveProfiles("test");

        assertThatCode(() -> new ProdProfileGuard().postProcessEnvironment(environment, application()))
                .doesNotThrowAnyException();

        environment.setActiveProfiles("test", "prod");
        assertThatThrownBy(() -> new ProdProfileGuard().postProcessEnvironment(environment, application()))
                .hasMessageContaining("필수 환경변수가 없습니다");
    }
}
