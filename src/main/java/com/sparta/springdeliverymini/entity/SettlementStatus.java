package com.sparta.springdeliverymini.entity;

// 정산 상태
//   PENDING(확정 대기) ─ confirm() ─▶ CONFIRMED(확정) ─ markPaid() ─▶ PAID(지급완료)
// - PENDING: 스케줄러가 하루치를 집계해 만든 상태. 금액은 고정되어 있고 관리자 확인을 기다림
// - CONFIRMED: 관리자가 금액을 확인해 확정한 상태. 지급 대상
// - PAID: 사장님에게 지급까지 끝난 최종 상태
// 거꾸로 가거나 단계를 건너뛰는 전이는 없음 (Settlement 메서드에서 검증)
public enum SettlementStatus {
    PENDING,
    CONFIRMED,
    PAID
}
