package com.sparta.springdeliverymini.entity;

// 결제 상태 — docs/settlement-policy.md 9장
//   COMPLETED ─ cancel() ─▶ CANCELLED  (배달 전, 주문수락 전 고객 취소 — 정산 대상이 아님)
//   COMPLETED ─ refund() ─▶ REFUNDED   (배달완료 후 관리자 전액 환불 — 차기 정산에서 차감)
// CANCELLED, REFUNDED는 최종 상태 (Payment 메서드에서 검증)
public enum PaymentStatus {
    COMPLETED,
    CANCELLED,
    REFUNDED
}
