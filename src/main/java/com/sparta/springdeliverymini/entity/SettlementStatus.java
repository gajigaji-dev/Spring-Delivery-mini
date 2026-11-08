package com.sparta.springdeliverymini.entity;

// 정산 상태
//   CONFIRMED(확정) ─ markPaid() ─▶ PAID(지급완료)
// - CONFIRMED: 확정 시각(D+1 01:00) 이후 집계해 금액이 고정된 상태. 이후 금액은 바뀌지 않음
// - PAID: 사장님에게 지급까지 끝난 최종 상태
public enum SettlementStatus {
    CONFIRMED,
    PAID
}
