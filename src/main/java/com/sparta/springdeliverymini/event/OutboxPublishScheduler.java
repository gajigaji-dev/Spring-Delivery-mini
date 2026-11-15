package com.sparta.springdeliverymini.event;

import com.sparta.springdeliverymini.config.OutboxProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.stereotype.Component;

import java.util.concurrent.ScheduledFuture;

// Outbox 주기 발행 — fixed-delay마다 OutboxPublisher.publishPending() 호출 (#42)
//
// - @Scheduled 대신 전용 스레드를 쓰는 이유: @EnableScheduling은 settlement.scheduler.enabled로 켜고 끔(config.SchedulingConfig)
//   → 정산 스케줄러를 끈 인스턴스에서 이벤트 발행까지 멈추거나, 발행을 위해 정산 스케줄러 설정을 바꾸지 않도록 분리
// - outbox.publisher.enabled=false면 빈을 만들지 않음 (이벤트 저장은 계속, 켜진 인스턴스가 발행)
// - 발행이 예외로 끝나도(DB 장애 등) 로그만 남기고 다음 주기에 다시 시도
// - 종료 시 진행 중인 발행이 끝나기를 잠시 기다림 → 못 끝낸 이벤트는 PENDING으로 남아 다음 기동 때 발행
@Slf4j
@Component
@ConditionalOnProperty(name = "outbox.publisher.enabled", havingValue = "true", matchIfMissing = true)
public class OutboxPublishScheduler implements SmartLifecycle {

    private final OutboxPublisher outboxPublisher;
    private final OutboxProperties properties;
    private final ThreadPoolTaskScheduler taskScheduler;
    private volatile ScheduledFuture<?> scheduled;

    public OutboxPublishScheduler(OutboxPublisher outboxPublisher, OutboxProperties properties) {
        this.outboxPublisher = outboxPublisher;
        this.properties = properties;
        this.taskScheduler = new ThreadPoolTaskScheduler();
        this.taskScheduler.setPoolSize(1); // 한 인스턴스 안에서는 한 번에 하나의 batch만 발행
        this.taskScheduler.setThreadNamePrefix("outbox-publisher-");
        this.taskScheduler.setWaitForTasksToCompleteOnShutdown(true);
        this.taskScheduler.setAwaitTerminationSeconds(10);
    }

    @Override
    public void start() {
        taskScheduler.initialize();
        scheduled = taskScheduler.scheduleWithFixedDelay(this::publish, properties.publisher().fixedDelay());
        log.info("Outbox 발행 시작 topic={}, fixedDelay={}", properties.topic(), properties.publisher().fixedDelay());
    }

    @Override
    public void stop() {
        ScheduledFuture<?> current = scheduled;
        if (current != null) {
            current.cancel(false);
        }
        taskScheduler.shutdown();
        scheduled = null;
    }

    @Override
    public boolean isRunning() {
        return scheduled != null;
    }

    private void publish() {
        try {
            outboxPublisher.publishPending();
        } catch (RuntimeException e) {
            log.error("Outbox 발행 실행 실패 — 다음 주기에 다시 시도", e);
        }
    }
}
