package com.sparta.springdeliverymini.dto;

import com.sparta.springdeliverymini.entity.SettlementStatus;
import com.sparta.springdeliverymini.entity.SettlementStatusHistory;

import java.time.LocalDateTime;

// 정산 상태 변경 이력 1건 (관리자 전용) — 누가 언제 어떤 상태로 바꿨는지
public record SettlementStatusHistoryResponse(
        Long id,
        SettlementStatus fromStatus,   // 이전 상태 (집계로 처음 만들어진 경우 null)
        SettlementStatus toStatus,     // 바뀐 상태
        SettlementActorResponse actor, // 처리자 (스케줄러 집계면 null)
        LocalDateTime changedAt) {     // 처리 시각

    public static SettlementStatusHistoryResponse from(SettlementStatusHistory history) {
        return new SettlementStatusHistoryResponse(
                history.getId(),
                history.getFromStatus(),
                history.getToStatus(),
                SettlementActorResponse.from(history.getActor()),
                history.getChangedAt());
    }
}
