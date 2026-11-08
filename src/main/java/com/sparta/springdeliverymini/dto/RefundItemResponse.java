package com.sparta.springdeliverymini.dto;

import com.sparta.springdeliverymini.entity.Payment;

import java.time.LocalDateTime;

// 환불 건별 내역 (OWNER 전용) — 환불된 결제의 금액이 그대로 취소됨
public record RefundItemResponse(
        Long paymentId,
        Long orderId,
        long amount,              // 환불 금액 (= 결제 금액, 전액 환불)
        long feeAmount,           // 취소된 플랫폼 수수료
        long settlementAmount,    // 차감되는 매장 정산액
        LocalDateTime deliveredAt, // 배달완료 시각 (판매로 정산된 날짜 기준)
        LocalDateTime refundedAt) { // 환불 시각

    public static RefundItemResponse from(Payment payment) {
        return new RefundItemResponse(
                payment.getId(),
                payment.getOrder().getId(),
                payment.getAmount(),
                payment.getFeeAmount(),
                payment.getSettlementAmount(),
                payment.getOrder().getDeliveredAt(),
                payment.getRefundedAt()
        );
    }
}
