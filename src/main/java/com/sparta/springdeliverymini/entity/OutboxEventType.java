package com.sparta.springdeliverymini.entity;

// Outbox로 발행하는 업무 이벤트 종류 (V13 outbox_events.event_type CHECK와 같은 목록)
// 새 종류를 추가하면 CHECK 제약도 새 마이그레이션으로 함께 바꿔야 함
public enum OutboxEventType {
    PAYMENT_COMPLETED,   // 결제 완료 (PaymentService.pay)
    PAYMENT_REFUNDED,    // 관리자 전액 환불 (AdminPaymentService.refund)
    DELIVERY_COMPLETED   // 배달 완료 (OrderService.statusOrder)
}
