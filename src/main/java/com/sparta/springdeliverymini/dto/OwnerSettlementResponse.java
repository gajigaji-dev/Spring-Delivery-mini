package com.sparta.springdeliverymini.dto;

import com.sparta.springdeliverymini.entity.Settlement;
import com.sparta.springdeliverymini.entity.SettlementStatus;

import java.time.LocalDate;
import java.time.LocalDateTime;

// 일 정산 기록 (OWNER 전용) — 저장된 일 정산(settlements) 1건, 실제 지급액·이월·상태 포함
// 관리자 응답(SettlementResponse)과 필드가 같아도 따로 둠 → 관리자 전용 필드가 생겨도 사장님에게 노출되지 않도록
// 지급액 = max(판매 정산액 - 환불 정산액 - 이월받은 금액, 0), 모자라면 다음 정산으로 이월 (docs/settlement-policy.md 9장)
public record OwnerSettlementResponse(
        Long id,
        LocalDate settlementDate,   // 정산 날짜 (배달완료 시각 기준, Asia/Seoul)
        int paymentCount,           // 판매 건수
        long totalAmount,           // 판매 결제 총액
        long totalFee,              // 판매 플랫폼 수수료 합계
        long salesSettlementAmount, // 판매 정산액 합계
        int refundCount,            // 이 정산에서 차감한 환불 건수
        long refundAmount,
        long refundFee,
        long refundSettlementAmount,// 이 정산에서 차감한 매장 정산액 합계
        long carriedInAmount,       // 이전 정산에서 이월받아 차감한 금액
        long carriedOutAmount,      // 다음 정산으로 넘긴 금액
        long settlementAmount,      // 실제 지급액 (≥ 0)
        SettlementStatus status,    // PENDING(확정 대기) / CONFIRMED / PAID — 금액은 집계 후 바뀌지 않음
        LocalDateTime aggregatedAt,
        LocalDateTime confirmedAt,  // PENDING이면 null
        LocalDateTime paidAt) {     // PAID가 아니면 null

    public static OwnerSettlementResponse from(Settlement s) {
        return new OwnerSettlementResponse(
                s.getId(), s.getSettlementDate(),
                s.getPaymentCount(), s.getTotalAmount(), s.getTotalFee(), s.getSalesSettlementAmount(),
                s.getRefundCount(), s.getRefundAmount(), s.getRefundFee(), s.getRefundSettlementAmount(),
                s.getCarriedInAmount(), s.getCarriedOutAmount(), s.getSettlementAmount(),
                s.getStatus(), s.getCreatedAt(), s.getConfirmedAt(), s.getPaidAt());
    }
}
