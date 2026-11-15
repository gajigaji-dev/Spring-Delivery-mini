package com.sparta.springdeliverymini.dto;

import com.sparta.springdeliverymini.entity.SettlementAggregationFailure;
import com.sparta.springdeliverymini.entity.SettlementAggregationFailureStatus;

import java.time.LocalDate;
import java.time.LocalDateTime;

// 정산 집계 실패 기록 (관리자 전용) — 매장·날짜당 1건
public record SettlementAggregationFailureResponse(
        Long id,
        Long storeId,
        String storeName,
        LocalDate settlementDate,
        SettlementAggregationFailureStatus status, // FAILED(재집계 대상) / RESOLVED
        int failureCount,                          // 지금까지 실패한 횟수
        String lastError,                          // 마지막 실패 원인 요약 (예외 종류·제약 조건 이름)
        LocalDateTime firstFailedAt,
        LocalDateTime lastFailedAt,
        SettlementActorResponse lastFailedBy,      // 마지막 실패를 실행한 관리자 (스케줄러면 null)
        LocalDateTime resolvedAt,                  // FAILED면 null
        SettlementActorResponse resolvedBy,        // 해결한 관리자 (스케줄러면 null)
        Long settlementId) {                       // 재집계로 만들어진 정산 (없으면 null)

    public static SettlementAggregationFailureResponse from(SettlementAggregationFailure failure) {
        return new SettlementAggregationFailureResponse(
                failure.getId(),
                failure.getStore().getId(),
                failure.getStore().getName(),
                failure.getSettlementDate(),
                failure.getStatus(),
                failure.getFailureCount(),
                failure.getLastError(),
                failure.getFirstFailedAt(),
                failure.getLastFailedAt(),
                SettlementActorResponse.from(failure.getLastFailedBy()),
                failure.getResolvedAt(),
                SettlementActorResponse.from(failure.getResolvedBy()),
                // 지연 로딩 프록시에서 id만 읽으므로 정산을 조회하지 않음
                failure.getSettlement() == null ? null : failure.getSettlement().getId());
    }
}
