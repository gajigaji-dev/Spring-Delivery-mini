package com.sparta.springdeliverymini.dto;

import com.sparta.springdeliverymini.entity.OutboxEvent;
import com.sparta.springdeliverymini.entity.OutboxEventStatus;
import com.sparta.springdeliverymini.entity.OutboxEventType;

import java.time.LocalDateTime;
import java.util.UUID;

// 관리자용 Outbox 이벤트 조회 응답 — 발행 실패 원인 확인·재발행 판단에 필요한 값
// payload(Kafka 메시지 본문)는 넣지 않음: 식별자·실패 원인만으로 판단할 수 있고, 본문은 재발행 때 그대로 다시 나감
public record OutboxEventResponse(
        Long id,
        UUID eventId,
        OutboxEventType eventType,
        String aggregateType,
        Long aggregateId,
        OutboxEventStatus status,
        int attemptCount,
        String lastError,
        LocalDateTime nextAttemptAt,
        LocalDateTime createdAt,
        LocalDateTime publishedAt
) {
    public static OutboxEventResponse from(OutboxEvent event) {
        return new OutboxEventResponse(
                event.getId(),
                event.getEventId(),
                event.getEventType(),
                event.getAggregateType(),
                event.getAggregateId(),
                event.getStatus(),
                event.getAttemptCount(),
                event.getLastError(),
                event.getNextAttemptAt(),
                event.getCreatedAt(),
                event.getPublishedAt()
        );
    }
}
