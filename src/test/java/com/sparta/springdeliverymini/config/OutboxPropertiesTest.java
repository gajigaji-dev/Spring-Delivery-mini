package com.sparta.springdeliverymini.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

// outbox.* 설정 바인딩·검증과 재시도 백오프 계산 (#42)
class OutboxPropertiesTest {

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(OutboxProperties.class)
    static class Config {
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(Config.class)
            .withPropertyValues(
                    "outbox.topic=delivery-events",
                    "outbox.publisher.enabled=true",
                    "outbox.publisher.fixed-delay=1s",
                    "outbox.publisher.batch-size=100",
                    "outbox.publisher.send-timeout=10s",
                    "outbox.publisher.max-attempts=10",
                    "outbox.publisher.initial-backoff=1s",
                    "outbox.publisher.max-backoff=5m",
                    "outbox.consumer.retry-attempts=3",
                    "outbox.consumer.retry-interval=1s");

    @Test
    void 설정을_바인딩하고_DLT_토픽_이름을_만든다() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            OutboxProperties properties = context.getBean(OutboxProperties.class);
            assertThat(properties.deadLetterTopic()).isEqualTo("delivery-events-dlt");
            assertThat(properties.publisher().maxAttempts()).isEqualTo(10);
            assertThat(properties.consumer().retryAttempts()).isEqualTo(3);
        });
    }

    @Test
    void 재시도_간격은_두_배씩_늘고_최대값에서_멈춘다() {
        runner.run(context -> {
            OutboxProperties.Publisher publisher = context.getBean(OutboxProperties.class).publisher();
            assertThat(publisher.backoff(1)).isEqualTo(Duration.ofSeconds(1));
            assertThat(publisher.backoff(2)).isEqualTo(Duration.ofSeconds(2));
            assertThat(publisher.backoff(5)).isEqualTo(Duration.ofSeconds(16));
            assertThat(publisher.backoff(9)).isEqualTo(Duration.ofMinutes(4).plusSeconds(16));
            assertThat(publisher.backoff(10)).isEqualTo(Duration.ofMinutes(5));
            assertThat(publisher.backoff(1000)).isEqualTo(Duration.ofMinutes(5));
        });
    }

    @Test
    void 잘못된_값이면_기동이_실패한다() {
        runner.withPropertyValues("outbox.publisher.batch-size=0").run(context -> assertThat(context).hasFailed());
        runner.withPropertyValues("outbox.publisher.max-backoff=500ms").run(context -> assertThat(context).hasFailed());
        runner.withPropertyValues("outbox.consumer.retry-attempts=-1").run(context -> assertThat(context).hasFailed());
        runner.withPropertyValues("outbox.topic= ").run(context -> assertThat(context).hasFailed());
    }
}
