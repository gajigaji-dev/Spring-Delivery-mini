package com.sparta.springdeliverymini.dto;

import com.sparta.springdeliverymini.entity.PaymentRefund;

import java.time.LocalDateTime;

// 환불 건별 내역 (OWNER 전용) — 환불 1건(V21, 부분 환불 포함)의 금액이 취소됨
public record RefundItemResponse(
        Long paymentId,
        Long orderId,
        long amount,              // 이번 환불 금액 (전액 환불이면 결제 금액)
        long feeAmount,           // 취소된 플랫폼 수수료
        long settlementAmount,    // 차감되는 매장 정산액
        LocalDateTime deliveredAt, // 배달완료 시각 (판매로 정산된 날짜 기준)
        LocalDateTime refundedAt,  // 환불 시각
        Long refundId) {          // 환불 1건 id (V21)

    public static RefundItemResponse from(PaymentRefund refund) {
        return new RefundItemResponse(
                refund.getPayment().getId(),
                refund.getPayment().getOrder().getId(),
                refund.getAmount(),
                refund.getFeeAmount(),
                refund.getSettlementAmount(),
                refund.getPayment().getOrder().getDeliveredAt(),
                refund.getRefundedAt(),
                refund.getId()
        );
    }
}
