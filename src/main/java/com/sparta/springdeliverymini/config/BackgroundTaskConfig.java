package com.sparta.springdeliverymini.config;

import com.sparta.springdeliverymini.event.LoggingNotificationSender;
import com.sparta.springdeliverymini.event.NotificationDispatcher;
import com.sparta.springdeliverymini.event.NotificationSender;
import com.sparta.springdeliverymini.event.SlackNotificationSender;
import com.sparta.springdeliverymini.maintenance.RetentionCleaner;
import com.sparta.springdeliverymini.scheduler.FixedDelayTask;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.ObjectMapper;

// 주기 작업 등록 — 알림 발송(발송 채널 선택 포함), 보관 기간 정리 (각각 전용 스레드, scheduler.FixedDelayTask)
// enabled=false면 빈을 만들지 않음 → 그 인스턴스는 실행하지 않고, 켜진 인스턴스가 처리
// 여러 인스턴스가 함께 켜져 있어도 대상 행을 FOR UPDATE SKIP LOCKED로 나눠 가지므로 분산 락이 필요 없음
@Configuration
public class BackgroundTaskConfig {

    @Bean
    @ConditionalOnProperty(name = "notification.dispatcher.enabled", havingValue = "true", matchIfMissing = true)
    FixedDelayTask notificationDispatchTask(NotificationDispatcher dispatcher, NotificationProperties properties) {
        return new FixedDelayTask("notification-dispatcher", properties.dispatcher().fixedDelay(),
                dispatcher::dispatchPending);
    }

    // 알림 발송 채널 — notification.sender: log(기본) / slack (#69)
    // 다른 NotificationSender 빈이 이미 있으면(테스트의 가짜 발송기 등) 만들지 않음
    @Bean
    @ConditionalOnMissingBean(NotificationSender.class)
    NotificationSender notificationSender(NotificationProperties properties, ObjectMapper objectMapper) {
        return switch (properties.sender()) {
            case LOG -> new LoggingNotificationSender();
            case SLACK -> SlackNotificationSender.create(properties.slack(), objectMapper);
        };
    }

    @Bean
    @ConditionalOnProperty(name = "retention.enabled", havingValue = "true", matchIfMissing = true)
    FixedDelayTask retentionCleanupTask(RetentionCleaner cleaner, RetentionProperties properties) {
        return new FixedDelayTask("retention-cleaner", properties.fixedDelay(), cleaner::cleanUp);
    }
}
