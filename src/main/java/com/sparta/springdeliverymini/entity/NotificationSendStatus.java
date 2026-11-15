package com.sparta.springdeliverymini.entity;

// 알림 발송 상태 (V14)
// PENDING → SENT (발송 성공) / PENDING → FAILED (재시도 한도 초과, 자동 발송 중단)
// SKIPPED: 발송 기능 도입 전에 저장된 기록 — 발송 대상 아님 (마이그레이션에서만 지정)
public enum NotificationSendStatus {
    PENDING, SENT, FAILED, SKIPPED
}
