package com.sparta.springdeliverymini.dto;

import com.sparta.springdeliverymini.entity.OrderStatus;
import com.sparta.springdeliverymini.entity.Payment;
import com.sparta.springdeliverymini.entity.PaymentMethod;

import java.time.LocalDateTime;

public record PaymentResponse(
        Long id,
        Long orderId,
        int amount,
        PaymentMethod paymentMethod,
        OrderStatus orderStatus, // 결제 후 주문 상태 (PAYMENT_COMPLETED)
        LocalDateTime paidAt) {

    public static PaymentResponse from(Payment payment) {
        return new PaymentResponse(
                payment.getId(),
                payment.getOrder().getId(),
                payment.getAmount(),
                payment.getPaymentMethod(),
                payment.getOrder().getStatus(),
                payment.getPaidAt()
        );
    }
}
