package com.sparta.springdeliverymini.dto;

import com.sparta.springdeliverymini.entity.Settlement;
import com.sparta.springdeliverymini.entity.SettlementStatus;

import java.time.LocalDate;
import java.time.LocalDateTime;

// 내 정산 내역 (OWNER 전용, GET /api/settlements) — 본인 매장 전체의 일 정산을 한 목록으로 보므로 매장 정보를 함께 담음
// 매장별 목록(OwnerSettlementResponse)은 응답 바깥에 매장이 1곳뿐이라 항목에 매장 필드가 없음 → 기존 응답 형식은 그대로 두고 따로 둠
// 관리자 응답(SettlementResponse)의 처리자(confirmedBy·paidBy)는 관리자 계정 정보라 사장님에게 노출하지 않음
public record MySettlementResponse(
        Long id,
        Long storeId,
        String storeName,
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

    public static MySettlementResponse from(Settlement s) {
        return new MySettlementResponse(
                s.getId(), s.getStore().getId(), s.getStore().getName(), s.getSettlementDate(),
                s.getPaymentCount(), s.getTotalAmount(), s.getTotalFee(), s.getSalesSettlementAmount(),
                s.getRefundCount(), s.getRefundAmount(), s.getRefundFee(), s.getRefundSettlementAmount(),
                s.getCarriedInAmount(), s.getCarriedOutAmount(), s.getSettlementAmount(),
                s.getStatus(), s.getCreatedAt(), s.getConfirmedAt(), s.getPaidAt());
    }
}
