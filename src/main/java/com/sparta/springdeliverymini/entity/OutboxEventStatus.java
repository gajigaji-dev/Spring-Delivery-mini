package com.sparta.springdeliverymini.entity;

// Outbox 이벤트 발행 상태 (V13 outbox_events.status)
// PENDING → PUBLISHED (발행 성공) / PENDING → FAILED (재시도 한도 초과)
// FAILED → PENDING (관리자 재발행, service.AdminOutboxService)
public enum OutboxEventStatus {
    PENDING,
    PUBLISHED,
    FAILED
}
