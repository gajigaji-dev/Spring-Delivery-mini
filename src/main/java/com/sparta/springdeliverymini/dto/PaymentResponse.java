package com.sparta.springdeliverymini.dto;

import com.sparta.springdeliverymini.entity.OrderStatus;
import com.sparta.springdeliverymini.entity.Payment;
import com.sparta.springdeliverymini.entity.PaymentMethod;
import com.sparta.springdeliverymini.entity.PaymentStatus;

import java.time.LocalDateTime;

public record PaymentResponse(
        Long id,
        Long orderId,
        long amount,
        PaymentMethod paymentMethod,
        OrderStatus orderStatus, // 주문 상태 (결제 직후 PAYMENT_COMPLETED)
        LocalDateTime paidAt,
        PaymentStatus status,          // COMPLETED / CANCELLED / REFUNDED
        LocalDateTime cancelledAt,     // 취소 시각 (CANCELLED가 아니면 null)
        LocalDateTime refundedAt) {    // 환불 시각 (REFUNDED가 아니면 null)

    public static PaymentResponse from(Payment payment) {
        return new PaymentResponse(
                payment.getId(),
                payment.getOrder().getId(),
                payment.getAmount(),
                payment.getPaymentMethod(),
                payment.getOrder().getStatus(),
                payment.getPaidAt(),
                payment.getStatus(),
                payment.getCancelledAt(),
                payment.getRefundedAt()
        );
    }
}
