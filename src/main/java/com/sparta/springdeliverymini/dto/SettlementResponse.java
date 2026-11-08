package com.sparta.springdeliverymini.dto;

import com.sparta.springdeliverymini.entity.Settlement;
import com.sparta.springdeliverymini.entity.SettlementStatus;

import java.time.LocalDate;
import java.time.LocalDateTime;

// 일 정산 (관리자 전용) — 매장 1곳의 하루치 정산과 상태
public record SettlementResponse(
        Long id,
        Long storeId,
        String storeName,
        LocalDate settlementDate,   // 정산 날짜 (배달완료 시각 기준, Asia/Seoul)
        int paymentCount,           // 정산 대상 결제 건수
        long totalAmount,           // 결제 총액
        long totalFee,              // 플랫폼 수수료 합계
        long settlementAmount,      // 사장님 정산액 합계 (= totalAmount - totalFee)
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
                settlement.getSettlementAmount(),
                settlement.getStatus(),
                settlement.getCreatedAt(),
                settlement.getConfirmedAt(),
                settlement.getPaidAt()
        );
    }
}
