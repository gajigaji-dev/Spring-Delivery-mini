package com.sparta.springdeliverymini.event;

import com.sparta.springdeliverymini.config.NotificationProperties;
import com.sparta.springdeliverymini.entity.Notification;
import com.sparta.springdeliverymini.repository.NotificationRepository;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;

// 알림 기록 → 발송 (V14, docs/architecture.md Kafka 장)
//
// - Consumer(NotificationService)는 기록만 저장하고, 발송은 여기서 따로 함
//   → 발송 채널이 느리거나 실패해도 Kafka 메시지 처리(오프셋 진행)를 막지 않고, 발송 실패가 DLT로 가지 않음
// - 발송할 행을 FOR UPDATE SKIP LOCKED로 잡고 결과(SENT / 실패 기록)를 같은 트랜잭션에서 저장 (OutboxPublisher와 같은 방식)
//   → 여러 인스턴스가 함께 돌아도 한 알림은 한 인스턴스만 발송
// - 최소 한 번 발송: 발송은 됐지만 결과 기록 전에 서버가 죽으면 다음에 다시 발송됨
// - 실패하면 retry-delay 뒤 재시도, max-attempts번 실패하면 FAILED로 남기고 자동 발송 중단
// - 메트릭: notification.send{result} = sent / retry / failed
@Slf4j
@Component
public class NotificationDispatcher {

    private final NotificationRepository notificationRepository;
    private final NotificationSender sender;
    private final NotificationProperties properties;
    private final Clock clock;
    private final MeterRegistry meterRegistry;

    public NotificationDispatcher(NotificationRepository notificationRepository, NotificationSender sender,
                                  NotificationProperties properties, Clock clock, MeterRegistry meterRegistry) {
        this.notificationRepository = notificationRepository;
        this.sender = sender;
        this.properties = properties;
        this.clock = clock;
        this.meterRegistry = meterRegistry;
        // 처음 실패하기 전에도 0으로 노출 → 알림 규칙(increase)이 첫 실패부터 잡음
        for (String result : List.of("sent", "retry", "failed")) {
            meterRegistry.counter("notification.send", "result", result);
        }
    }

    // 발송 대상 1 batch를 발송하고 결과 반환
    @Transactional
    public DispatchResult dispatchPending() {
        NotificationProperties.Dispatcher dispatcher = properties.dispatcher();
        List<Notification> notifications = notificationRepository.findSendableForUpdate(
                LocalDateTime.now(clock), dispatcher.batchSize());

        int sent = 0;
        int retry = 0;
        int failed = 0;
        for (Notification notification : notifications) {
            try {
                sender.send(notification);
                notification.markSent(LocalDateTime.now(clock));
                sent++;
            } catch (RuntimeException e) {
                // 한 건의 실패가 batch의 나머지 발송을 막지 않도록 건별로 기록
                notification.recordSendFailure(summary(e), LocalDateTime.now(clock),
                        dispatcher.maxAttempts(), dispatcher.retryDelay());
                if (notification.isSendPending()) {
                    retry++;
                    log.warn("알림 발송 실패 — 재시도 예정 notificationId={}, attempt={}, nextSendAt={}, error={}",
                            notification.getId(), notification.getSendAttemptCount(),
                            notification.getNextSendAt(), notification.getLastSendError());
                } else {
                    failed++;
                    log.error("알림 발송 재시도 한도 초과 — FAILED notificationId={}, attempt={}, error={}",
                            notification.getId(), notification.getSendAttemptCount(), notification.getLastSendError());
                }
            }
        }
        count("sent", sent);
        count("retry", retry);
        count("failed", failed);
        return new DispatchResult(sent, retry, failed);
    }

    private void count(String result, int amount) {
        if (amount > 0) {
            meterRegistry.counter("notification.send", "result", result).increment(amount);
        }
    }

    private static String summary(Throwable e) {
        String message = e.getMessage();
        return message == null ? e.getClass().getName() : e.getClass().getName() + ": " + message;
    }

    // sent: 발송 성공 / retry: 실패했지만 재시도 예정 / failed: 재시도 한도 초과로 FAILED
    public record DispatchResult(int sent, int retry, int failed) {
    }
}
