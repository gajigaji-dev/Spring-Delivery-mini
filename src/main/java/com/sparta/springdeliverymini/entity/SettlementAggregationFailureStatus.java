package com.sparta.springdeliverymini.entity;

// 정산 집계 실패 기록의 상태 (V11)
public enum SettlementAggregationFailureStatus {
    FAILED,   // 집계가 실패해 정산이 없는 상태 — 재집계 대상
    RESOLVED  // 이후 집계가 성공했거나 집계할 대상이 없음을 확인함
}
