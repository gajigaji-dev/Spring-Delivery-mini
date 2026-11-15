package com.sparta.springdeliverymini.scheduler;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.SmartLifecycle;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import java.time.Duration;
import java.util.concurrent.ScheduledFuture;

// 전용 스레드 하나로 작업을 fixed-delay마다 실행 (알림 발송·보관 기간 정리, config.BackgroundTaskConfig에서 빈 등록)
//
// - @Scheduled를 쓰지 않는 이유는 event.OutboxPublishScheduler와 같음:
//   @EnableScheduling은 settlement.scheduler.enabled로 켜고 끄므로, 정산 스케줄러 설정과 무관하게 돌도록 분리
// - 작업이 예외로 끝나도 로그만 남기고 다음 주기에 다시 실행
// - 종료 시 진행 중인 작업이 끝나기를 잠시 기다림
@Slf4j
public class FixedDelayTask implements SmartLifecycle {

    private final String name;
    private final Duration fixedDelay;
    private final Runnable task;
    private final ThreadPoolTaskScheduler taskScheduler;
    private volatile ScheduledFuture<?> scheduled;

    public FixedDelayTask(String name, Duration fixedDelay, Runnable task) {
        this.name = name;
        this.fixedDelay = fixedDelay;
        this.task = task;
        this.taskScheduler = new ThreadPoolTaskScheduler();
        this.taskScheduler.setPoolSize(1); // 한 인스턴스 안에서는 같은 작업이 겹쳐 실행되지 않음
        this.taskScheduler.setThreadNamePrefix(name + "-");
        this.taskScheduler.setWaitForTasksToCompleteOnShutdown(true);
        this.taskScheduler.setAwaitTerminationSeconds(10);
    }

    @Override
    public void start() {
        taskScheduler.initialize();
        scheduled = taskScheduler.scheduleWithFixedDelay(this::run, fixedDelay);
        log.info("주기 작업 시작 name={}, fixedDelay={}", name, fixedDelay);
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

    private void run() {
        try {
            task.run();
        } catch (RuntimeException e) {
            log.error("주기 작업 실행 실패 — 다음 주기에 다시 시도 name={}", name, e);
        }
    }
}
