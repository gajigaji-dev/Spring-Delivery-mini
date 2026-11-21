package com.sparta.springdeliverymini.entity;

// PG 결제 취소 요청 상태 (V20, PgCancelRequest)
//   PENDING ─ 성공·이미 취소됨 ─▶ DONE
//   PENDING ─ max-attempts번 실패 ─▶ FAILED (자동 재시도 중단, 운영자 확인 대상 — pg.cancel.failed 메트릭)
public enum PgCancelStatus {
    PENDING,
    DONE,
    FAILED
}
