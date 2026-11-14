package com.sparta.springdeliverymini.dto;

import com.sparta.springdeliverymini.service.SettlementAggregationService.Outcome;
import com.sparta.springdeliverymini.service.SettlementAggregationService.StoreResult;

import java.time.LocalDate;
import java.util.List;

// 관리자 수동 재집계 결과 — 매장별 처리 결과와 합계
public record SettlementReaggregationResponse(
        LocalDate date,
        int created,  // 새로 만든 정산 수
        int skipped,  // 이미 정산이 있거나 집계 대상이 없어 건너뛴 매장 수
        int failed,   // 다시 실패한 매장 수 (실패 기록에 남음)
        List<StoreItem> results) {

    public record StoreItem(
            Long storeId,
            Outcome outcome,   // CREATED / ALREADY_EXISTS / NO_TARGET / FAILED
            Long settlementId, // CREATED일 때만
            String error) {    // FAILED일 때만 — 원인 요약
    }

    public static SettlementReaggregationResponse of(LocalDate date, List<StoreResult> results) {
        int created = 0;
        int skipped = 0;
        int failed = 0;
        for (StoreResult result : results) {
            switch (result.outcome()) {
                case CREATED -> created++;
                case FAILED -> failed++;
                default -> skipped++;
            }
        }
        return new SettlementReaggregationResponse(date, created, skipped, failed,
                results.stream()
                        .map(r -> new StoreItem(r.storeId(), r.outcome(), r.settlementId(), r.error()))
                        .toList());
    }
}
