package com.sparta.springdeliverymini.dto;

import com.sparta.springdeliverymini.entity.PaymentRefund;
import com.sparta.springdeliverymini.entity.PaymentStatus;

import java.time.LocalDateTime;

// 부분 환불 결과 (V21, 관리자) — 이번 환불 1건 + 결제의 누적 환불 상태
public record PaymentRefundResponse(
        Long refundId,
        Long paymentId,
        Long orderId,
        long amount,             // 이번 환불 금액
        long feeAmount,          // 함께 취소된 플랫폼 수수료 (환불액 비율, 누적 반올림)
        long settlementAmount,   // 다음 정산에서 차감되는 매장 정산액
        String reason,
        LocalDateTime refundedAt,
        PaymentStatus paymentStatus,  // PARTIALLY_REFUNDED / REFUNDED(남은 금액 전부 환불)
        long refundedAmount,     // 누적 환불액
        long refundableAmount) { // 남은 환불 가능 금액

    public static PaymentRefundResponse of(PaymentRefund refund) {
        var payment = refund.getPayment();
        return new PaymentRefundResponse(
                refund.getId(),
                payment.getId(),
                payment.getOrder().getId(),
                refund.getAmount(),
                refund.getFeeAmount(),
                refund.getSettlementAmount(),
                refund.getReason(),
                refund.getRefundedAt(),
                payment.getStatus(),
                payment.getRefundedAmount(),
                payment.refundableAmount());
    }
}
