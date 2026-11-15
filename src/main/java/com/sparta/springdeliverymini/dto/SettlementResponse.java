package com.sparta.springdeliverymini.dto;

import com.sparta.springdeliverymini.entity.Settlement;
import com.sparta.springdeliverymini.entity.SettlementStatus;
import com.sparta.springdeliverymini.entity.SettlementStatusHistory;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

// 일 정산 (관리자 전용) — 매장 1곳의 하루치 정산과 상태
// 지급액 = max(판매 정산액 - 환불 정산액 - 이월받은 금액, 0), 모자라면 다음 정산으로 이월 (docs/settlement-policy.md 9장)
// confirmedBy·paidBy는 상태 변경 이력(settlement_status_histories, V11)에서 가져온 처리자 — 기존 필드 뒤에 추가만 함
public record SettlementResponse(
        Long id,
        Long storeId,
        String storeName,
        LocalDate settlementDate,   // 정산 날짜 (배달완료 시각 기준, Asia/Seoul)
        int paymentCount,           // 판매 건수 (이날 배달완료된 결제)
        long totalAmount,           // 판매 결제 총액
        long totalFee,              // 판매 플랫폼 수수료 합계
        long salesSettlementAmount, // 판매 정산액 합계 (= totalAmount - totalFee)
        int refundCount,            // 이 정산에서 차감한 환불 건수
        long refundAmount,          // 환불 총액
        long refundFee,             // 취소된 플랫폼 수수료 합계
        long refundSettlementAmount,// 차감한 매장 정산액 합계
        long carriedInAmount,       // 이전 정산에서 이월받아 차감한 금액
        long carriedOutAmount,      // 다음 정산으로 넘긴 금액
        long settlementAmount,      // 실제 지급액 (≥ 0)
        SettlementStatus status,    // PENDING / CONFIRMED / PAID
        LocalDateTime aggregatedAt, // 집계 시각 (created_at)
        LocalDateTime confirmedAt,  // 확정 시각 (PENDING이면 null)
        LocalDateTime paidAt,       // 지급 시각 (PAID가 아니면 null)
        SettlementActorResponse confirmedBy, // 확정한 관리자 (확정 전이거나 V11 전에 확정된 정산이면 null)
        SettlementActorResponse paidBy) {    // 지급한 관리자 (지급 전이거나 V11 전에 지급된 정산이면 null)

    // histories: 이 정산의 상태 변경 이력 (다른 정산의 이력이 섞여 있으면 안 됨)
    public static SettlementResponse from(Settlement settlement, List<SettlementStatusHistory> histories) {
        return new SettlementResponse(
                settlement.getId(),
                settlement.getStore().getId(),
                settlement.getStore().getName(),
                settlement.getSettlementDate(),
                settlement.getPaymentCount(),
                settlement.getTotalAmount(),
                settlement.getTotalFee(),
                settlement.getSalesSettlementAmount(),
                settlement.getRefundCount(),
                settlement.getRefundAmount(),
                settlement.getRefundFee(),
                settlement.getRefundSettlementAmount(),
                settlement.getCarriedInAmount(),
                settlement.getCarriedOutAmount(),
                settlement.getSettlementAmount(),
                settlement.getStatus(),
                settlement.getCreatedAt(),
                settlement.getConfirmedAt(),
                settlement.getPaidAt(),
                actorOf(histories, SettlementStatus.CONFIRMED),
                actorOf(histories, SettlementStatus.PAID)
        );
    }

    // toStatus로 바꾼 처리자 — (settlement_id, to_status) UNIQUE라 많아야 1건
    private static SettlementActorResponse actorOf(List<SettlementStatusHistory> histories, SettlementStatus toStatus) {
        return histories.stream()
                .filter(history -> history.getToStatus() == toStatus)
                .findFirst()
                .map(history -> SettlementActorResponse.from(history.getActor()))
                .orElse(null);
    }
}
