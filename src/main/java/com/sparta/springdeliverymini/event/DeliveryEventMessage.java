package com.sparta.springdeliverymini.event;

import com.sparta.springdeliverymini.entity.OutboxEventType;

import java.time.LocalDateTime;
import java.util.UUID;

// Kafka로 보내는 업무 이벤트 본문 (outbox_events.payload에 JSON으로 저장된 값 그대로 발행)
// - 발생 시점 값의 스냅샷: Consumer는 DB를 다시 조회하지 않고 이 값만으로 처리
// - eventId: Consumer 멱등성 기준 (consumed_events)
// - paymentId: 배달완료 이벤트는 결제를 다루지 않으므로 null
// - amount: 결제·환불은 결제 금액, 배달완료는 주문 총액 (원)
// 필드를 바꾸면 이미 쌓인 Outbox·Kafka 메시지와 호환되는지 함께 확인 (모르는 필드는 무시, 필수 값이 빠지면 DLT)
public record DeliveryEventMessage(
        UUID eventId,
        OutboxEventType eventType,
        Long orderId,
        Long paymentId,
        Long storeId,
        Long customerId,
        Long ownerId,
        long amount,
        LocalDateTime occurredAt
) {

    // 수신한 메시지의 필수 값 확인 — 빠졌으면 재시도해도 같으므로 바로 DLT
    void validate() {
        if (eventId == null || eventType == null || orderId == null || storeId == null
                || customerId == null || ownerId == null || occurredAt == null) {
            throw new InvalidEventMessageException("필수 값이 없는 이벤트입니다: eventId=" + eventId);
        }
    }
}
