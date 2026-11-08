package com.sparta.springdeliverymini.dto;

import com.sparta.springdeliverymini.entity.Settlement;
import com.sparta.springdeliverymini.entity.SettlementStatus;

import java.time.LocalDate;
import java.time.LocalDateTime;

// 일 정산 (관리자 전용) — 매장 1곳의 하루치 정산과 상태
// 지급액 = max(판매 정산액 - 환불 정산액 - 이월받은 금액, 0), 모자라면 다음 정산으로 이월 (docs/settlement-policy.md 9장)
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
        LocalDateTime paidAt) {     // 지급 시각 (PAID가 아니면 null)

    public static SettlementResponse from(Settlement settlement) {
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
                settlement.getPaidAt()
        );
    }
}
