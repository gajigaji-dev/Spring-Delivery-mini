package com.sparta.springdeliverymini.entity;

import com.sparta.springdeliverymini.exception.InvalidOutboxEventStatusException;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import jakarta.persistence.*;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.UUID;

// 발행 대기 이벤트 (Transactional Outbox, V13) — docs/architecture.md Kafka 장
//
// - 결제·환불·배달완료 트랜잭션 안에서 업무 데이터와 함께 저장 → 업무 데이터가 커밋되면 이벤트도 반드시 남고,
//   롤백되면 이벤트도 남지 않음 (트랜잭션 안에서 Kafka로 직접 보내면 "보냈는데 롤백" / "커밋했는데 못 보냄"이 생김)
// - Kafka 발행은 트랜잭션 밖의 Publisher(event.OutboxPublisher)가 따로 수행 → Kafka 장애가 결제 등 핵심 트랜잭션을 실패시키지 않음
// - payload는 발생 시점 값의 JSON 스냅샷 (event.DeliveryEventMessage) — Kafka 메시지 본문 그대로
// - 발행 상태·재시도 정보만 UPDATE함 (업무 금액 기록이 아니므로 결제·정산의 불변 규칙 대상 아님)
@Entity
@Table(name = "outbox_events")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OutboxEvent {

    static final int MAX_ERROR_LENGTH = 500;

    // 지금은 모두 주문 단위 이벤트 → Kafka 메시지 키도 주문 id (같은 주문의 이벤트는 같은 파티션)
    public static final String AGGREGATE_ORDER = "ORDER";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // Consumer 멱등성 기준 (UNIQUE)
    @Column(nullable = false, updatable = false, unique = true)
    private UUID eventId;

    @Column(nullable = false, updatable = false, length = 30)
    private String aggregateType;

    @Column(nullable = false, updatable = false)
    private Long aggregateId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 50)
    private OutboxEventType eventType;

    @Column(nullable = false, updatable = false, columnDefinition = "text")
    private String payload;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private OutboxEventStatus status;

    // 발행 실패 횟수 (성공한 시도는 세지 않음)
    @Column(nullable = false)
    private int attemptCount;

    // 이 시각 이후 발행 시도 (실패하면 백오프만큼 뒤로 밀림)
    @Column(nullable = false)
    private LocalDateTime nextAttemptAt;

    // 마지막 실패 원인 요약 (예외 종류·메시지 앞부분)
    @Column(length = MAX_ERROR_LENGTH)
    private String lastError;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    // PUBLISHED일 때만 있음 (DB CHECK)
    private LocalDateTime publishedAt;

    private OutboxEvent(UUID eventId, OutboxEventType eventType, Long aggregateId, String payload,
                        LocalDateTime createdAt) {
        this.eventId = eventId;
        this.aggregateType = AGGREGATE_ORDER;
        this.aggregateId = aggregateId;
        this.eventType = eventType;
        this.payload = payload;
        this.status = OutboxEventStatus.PENDING;
        this.attemptCount = 0;
        this.nextAttemptAt = createdAt;
        this.createdAt = createdAt;
    }

    // 주문 단위 이벤트 — 만들자마자 발행 대상(next_attempt_at = 생성 시각)
    public static OutboxEvent pending(UUID eventId, OutboxEventType eventType, Long orderId, String payload,
                                      LocalDateTime createdAt) {
        if (eventId == null || eventType == null || orderId == null || payload == null || createdAt == null) {
            throw new IllegalArgumentException("Outbox 이벤트에 필요한 값이 없습니다.");
        }
        return new OutboxEvent(eventId, eventType, orderId, payload, createdAt);
    }

    public boolean isPending() {
        return status == OutboxEventStatus.PENDING;
    }

    // Kafka 발행 확인 → PUBLISHED (PENDING에서만)
    public void markPublished(LocalDateTime publishedAt) {
        if (publishedAt == null) {
            throw new IllegalArgumentException("발행 시각이 필요합니다.");
        }
        requirePending();
        this.status = OutboxEventStatus.PUBLISHED;
        this.publishedAt = publishedAt;
    }

    // 발행 실패 — 실패 횟수가 maxAttempts에 닿으면 FAILED(자동 재시도 중단), 아니면 retryDelay 뒤 재시도
    public void recordFailure(String error, LocalDateTime failedAt, int maxAttempts, Duration retryDelay) {
        if (failedAt == null || retryDelay == null || retryDelay.isNegative() || maxAttempts < 1) {
            throw new IllegalArgumentException("발행 실패 기록에 필요한 값이 올바르지 않습니다.");
        }
        requirePending();
        this.attemptCount = Math.addExact(attemptCount, 1);
        this.lastError = truncate(error);
        if (attemptCount >= maxAttempts) {
            this.status = OutboxEventStatus.FAILED;
        } else {
            this.nextAttemptAt = failedAt.plus(retryDelay);
        }
    }

    // 관리자 재발행 — FAILED → PENDING (FAILED에서만, 위반 시 409)
    // 실패 횟수를 0으로 되돌려 재시도 한도를 처음부터 다시 적용. 마지막 실패 원인은 확인용으로 남김
    // 이미 Kafka에 저장됐지만 결과 기록 전에 FAILED가 된 이벤트라면 같은 eventId가 다시 가고, Consumer가 중복을 걸러냄
    public void republish(LocalDateTime now) {
        if (now == null) {
            throw new IllegalArgumentException("재발행 시각이 필요합니다.");
        }
        if (status != OutboxEventStatus.FAILED) {
            throw new InvalidOutboxEventStatusException(
                    "발행 실패(FAILED) 상태의 이벤트만 재발행할 수 있습니다. 현재 상태: " + status);
        }
        this.status = OutboxEventStatus.PENDING;
        this.attemptCount = 0;
        this.nextAttemptAt = now;
    }

    private void requirePending() {
        if (!isPending()) {
            throw new IllegalStateException("발행 대기(PENDING) 상태의 이벤트만 처리할 수 있습니다. 현재 상태: " + status);
        }
    }

    private static String truncate(String error) {
        if (error == null || error.isBlank()) {
            return "unknown";
        }
        return error.length() <= MAX_ERROR_LENGTH ? error : error.substring(0, MAX_ERROR_LENGTH);
    }
}
