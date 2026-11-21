package com.sparta.springdeliverymini.entity;

// 결제 상태 — docs/settlement-policy.md 9장
//   COMPLETED ─ cancel() ─▶ CANCELLED            (배달 전, 주문수락 전 고객 취소 — 정산 대상이 아님)
//   COMPLETED ─ refund(일부) ─▶ PARTIALLY_REFUNDED (배달완료 후 관리자 부분 환불, V21 — 환불분은 차기 정산에서 차감)
//   COMPLETED·PARTIALLY_REFUNDED ─ refund(남은 금액 전부) ─▶ REFUNDED (전액 환불 완료)
// CANCELLED, REFUNDED는 최종 상태 (Payment 메서드에서 검증)
public enum PaymentStatus {
    COMPLETED,
    CANCELLED,
    PARTIALLY_REFUNDED,
    REFUNDED
}
