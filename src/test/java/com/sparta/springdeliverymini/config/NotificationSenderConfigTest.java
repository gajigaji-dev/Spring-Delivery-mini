package com.sparta.springdeliverymini.config;

import com.sparta.springdeliverymini.event.LoggingNotificationSender;
import com.sparta.springdeliverymini.event.NotificationSender;
import com.sparta.springdeliverymini.event.SlackNotificationSender;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

// notification.sender 설정에 따른 발송기 선택·검증 (#69, config.BackgroundTaskConfig)
class NotificationSenderConfigTest {

    private static final String WEBHOOK_URL = "https://hooks.slack.com/services/T000SECRET/B000SECRET/xyzSecretToken";

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(NotificationProperties.class)
    static class Config {
        @Bean
        ObjectMapper objectMapper() {
            return JsonMapper.builder().build();
        }
    }

    // 주기 작업은 끄고 발송기 빈만 확인 (발송 작업은 NotificationDispatcher·DB가 필요)
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(Config.class, BackgroundTaskConfig.class)
            .withPropertyValues(
                    "notification.dispatcher.enabled=false",
                    "notification.dispatcher.fixed-delay=5s",
                    "notification.dispatcher.batch-size=100",
                    "notification.dispatcher.max-attempts=5",
                    "notification.dispatcher.retry-delay=1m",
                    "notification.slack.connect-timeout=2s",
                    "notification.slack.read-timeout=3s",
                    "retention.enabled=false");

    @Test
    void sender를_지정하지_않으면_로그_발송기를_쓴다() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(NotificationSender.class)).isInstanceOf(LoggingNotificationSender.class);
            assertThat(context.getBean(NotificationProperties.class).sender()).isEqualTo(NotificationProperties.Sender.LOG);
        });
    }

    @Test
    void sender가_log면_웹훅_URL이_없어도_기동한다() {
        runner.withPropertyValues("notification.sender=log").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(NotificationSender.class)).isInstanceOf(LoggingNotificationSender.class);
        });
    }

    @Test
    void sender가_slack이고_URL이_있으면_Slack_발송기를_쓴다() {
        runner.withPropertyValues("notification.sender=slack", "notification.slack.webhook-url=" + WEBHOOK_URL)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(NotificationSender.class)).isInstanceOf(SlackNotificationSender.class);
                    NotificationProperties.Slack slack = context.getBean(NotificationProperties.class).slack();
                    assertThat(slack.connectTimeout()).isEqualTo(Duration.ofSeconds(2));
                    assertThat(slack.readTimeout()).isEqualTo(Duration.ofSeconds(3));
                    // 설정 객체가 문자열로 찍혀도 URL은 가려짐
                    assertThat(slack.toString()).doesNotContain("SECRET").contains("******");
                });
    }

    @Test
    void sender가_slack인데_URL이_없으면_기동에_실패한다() {
        runner.withPropertyValues("notification.sender=slack", "notification.slack.webhook-url=")
                .run(context -> assertThat(context).hasFailed()
                        .getFailure().rootCause().hasMessageContaining("SLACK_WEBHOOK_URL"));
    }

    @Test
    void 웹훅_URL_형식이_잘못되면_값을_출력하지_않고_기동에_실패한다() {
        runner.withPropertyValues("notification.sender=slack", "notification.slack.webhook-url=not-a-url-SECRET")
                .run(context -> assertThat(context).hasFailed()
                        .getFailure().rootCause()
                        .hasMessageContaining("http(s) 절대 주소")
                        .hasMessageNotContaining("SECRET"));
    }

    @Test
    void slack_시간_초과가_0이면_기동에_실패한다() {
        runner.withPropertyValues("notification.sender=slack", "notification.slack.webhook-url=" + WEBHOOK_URL,
                        "notification.slack.read-timeout=0s")
                .run(context -> assertThat(context).hasFailed()
                        .getFailure().rootCause().hasMessageContaining("slack.read-timeout"));
    }

    @Test
    void 알_수_없는_sender_값이면_기동에_실패한다() {
        runner.withPropertyValues("notification.sender=email").run(context -> assertThat(context).hasFailed());
    }

    @Test
    void 다른_발송기_빈이_있으면_설정과_관계없이_그_빈을_쓴다() {
        NotificationSender custom = notification -> { };
        runner.withPropertyValues("notification.sender=slack", "notification.slack.webhook-url=" + WEBHOOK_URL)
                .withBean(NotificationSender.class, () -> custom)
                .run(context -> assertThat(context.getBean(NotificationSender.class)).isSameAs(custom));
    }
}
