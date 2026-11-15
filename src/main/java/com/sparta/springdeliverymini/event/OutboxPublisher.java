package com.sparta.springdeliverymini.event;

import com.sparta.springdeliverymini.config.OutboxProperties;
import com.sparta.springdeliverymini.entity.OutboxEvent;
import com.sparta.springdeliverymini.repository.OutboxEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

// Outbox → Kafka 발행 (#42, docs/architecture.md Kafka 장)
//
// - 업무 트랜잭션과 분리된 별도 트랜잭션에서 실행 (OutboxPublishScheduler가 주기 호출)
//   → Kafka 장애·지연은 이 발행만 늦출 뿐 결제·환불·배달완료 처리에는 영향 없음
// - 발행할 행을 FOR UPDATE SKIP LOCKED로 잡고, 발행 결과(PUBLISHED / 실패 기록)를 같은 트랜잭션에서 저장
//   → 여러 인스턴스가 함께 돌아도 한 이벤트는 한 인스턴스만 발행
// - 최소 한 번 발행: Kafka 저장은 성공했지만 결과 기록 전에 서버가 죽거나 응답이 시간 초과되면 다음에 다시 발행됨
//   → 같은 eventId가 두 번 갈 수 있고, Consumer가 eventId로 중복을 걸러냄 (consumed_events)
// - 실패 시 지수 백오프로 재시도, max-attempts번 실패하면 FAILED로 남기고 자동 재시도 중단
//   (Kafka에 보낼 수 없는 상황이라 DLT 대신 DB에 남김 → 원인 확인 후 운영자가 처리)
@Slf4j
@Component
@RequiredArgsConstructor
public class OutboxPublisher {

    // Kafka 헤더 — Consumer·DLT에서 본문을 열지 않고 이벤트를 식별하기 위함
    public static final String HEADER_EVENT_ID = "eventId";
    public static final String HEADER_EVENT_TYPE = "eventType";

    private final OutboxEventRepository outboxEventRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final OutboxProperties properties;
    private final Clock clock;

    // 발행 대상 1 batch를 발행하고 결과 반환
    @Transactional
    public PublishResult publishPending() {
        OutboxProperties.Publisher publisher = properties.publisher();
        List<OutboxEvent> events = outboxEventRepository.findPublishableForUpdate(
                LocalDateTime.now(clock), publisher.batchSize());
        if (events.isEmpty()) {
            return new PublishResult(0, 0, 0);
        }

        // 먼저 모두 보내 놓고 결과를 기다림 — 건별로 기다리면 batch 크기 × 왕복 시간이 걸림
        // send가 바로 실패하면(Kafka 접속 불가 → 메타데이터를 max.block.ms 동안 기다린 뒤 예외) 나머지는 이번 주기에 보내지 않음
        //   → 건마다 max.block.ms씩 묶여 batch 크기 × 5초 동안 행 잠금을 쥐는 일을 막음
        //   보내지 않은 이벤트는 그대로 PENDING(시도 횟수 증가 없음) → 다음 주기에 다시 시도
        List<CompletableFuture<?>> futures = new ArrayList<>(events.size());
        for (OutboxEvent event : events) {
            CompletableFuture<?> future = send(event);
            futures.add(future);
            if (future.isCompletedExceptionally()) {
                break;
            }
        }

        // batch 전체에 send-timeout 한 번만 적용 (건마다 적용하면 최악의 경우 batch 크기 × timeout 동안 행 잠금 유지)
        long deadline = System.nanoTime() + publisher.sendTimeout().toNanos();
        int published = 0;
        int retry = 0;
        int failed = 0;
        for (int i = 0; i < futures.size(); i++) {
            OutboxEvent event = events.get(i);
            try {
                futures.get(i).get(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
                event.markPublished(LocalDateTime.now(clock));
                published++;
            } catch (InterruptedException e) {
                // 종료 중 — 이번 batch의 남은 결과는 기록하지 않음(PENDING 유지 → 다음에 재발행, Consumer가 중복 제거)
                Thread.currentThread().interrupt();
                break;
            } catch (ExecutionException | TimeoutException | RuntimeException e) {
                Throwable cause = e instanceof ExecutionException && e.getCause() != null ? e.getCause() : e;
                int failureCount = event.getAttemptCount() + 1;
                event.recordFailure(summary(cause), LocalDateTime.now(clock),
                        publisher.maxAttempts(), publisher.backoff(failureCount));
                if (event.isPending()) {
                    retry++;
                    log.warn("Outbox 발행 실패 — 재시도 예정 eventId={}, type={}, attempt={}, nextAttemptAt={}, error={}",
                            event.getEventId(), event.getEventType(), event.getAttemptCount(),
                            event.getNextAttemptAt(), event.getLastError());
                } else {
                    failed++;
                    log.error("Outbox 발행 재시도 한도 초과 — FAILED eventId={}, type={}, attempt={}, error={}",
                            event.getEventId(), event.getEventType(), event.getAttemptCount(), event.getLastError());
                }
            }
        }
        if (published > 0) {
            log.debug("Outbox 발행 published={}, retry={}, failed={}", published, retry, failed);
        }
        return new PublishResult(published, retry, failed);
    }

    // 메시지 키 = 주문 id → 같은 주문의 이벤트는 같은 파티션
    // 메타데이터를 못 받는 등 send 자체가 예외를 던져도 실패 결과로 바꿔 다른 이벤트 발행은 계속
    private CompletableFuture<?> send(OutboxEvent event) {
        ProducerRecord<String, String> record = new ProducerRecord<>(
                properties.topic(), String.valueOf(event.getAggregateId()), event.getPayload());
        record.headers()
                .add(HEADER_EVENT_ID, event.getEventId().toString().getBytes(StandardCharsets.UTF_8))
                .add(HEADER_EVENT_TYPE, event.getEventType().name().getBytes(StandardCharsets.UTF_8));
        try {
            return kafkaTemplate.send(record);
        } catch (RuntimeException e) {
            return CompletableFuture.failedFuture(e);
        }
    }

    private static String summary(Throwable e) {
        String message = e.getMessage();
        return message == null ? e.getClass().getName() : e.getClass().getName() + ": " + message;
    }

    // published: 발행 성공 / retry: 실패했지만 재시도 예정 / failed: 재시도 한도 초과로 FAILED
    public record PublishResult(int published, int retry, int failed) {
    }
}
