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
        PaymentStatus status,          // COMPLETED / CANCELLED / PARTIALLY_REFUNDED / REFUNDED
        LocalDateTime cancelledAt,     // 취소 시각 (CANCELLED가 아니면 null)
        LocalDateTime refundedAt,      // 전액 환불이 끝난 시각 (REFUNDED가 아니면 null)
        long refundedAmount) {         // 누적 환불액 (V21 부분 환불 포함, 환불 없으면 0)

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
                payment.getRefundedAt(),
                payment.getRefundedAmount()
        );
    }

    // 결제 직후의 응답을 다시 만듦 — DB 멱등성 기록으로 처음 응답을 재전송할 때 사용 (#41 후속, PaymentService.findIdempotentPayment)
    // 결제 금액·수단·시각은 생성 후 바뀌지 않고, 결제 직후 상태는 항상 PAYMENT_COMPLETED / COMPLETED
    // → 이후 주문이 배달완료·환불됐어도 처음 응답과 같은 값 (현재 상태는 결제 조회 API로 확인)
    public static PaymentResponse atPayment(Payment payment) {
        return new PaymentResponse(
                payment.getId(),
                payment.getOrder().getId(),
                payment.getAmount(),
                payment.getPaymentMethod(),
                OrderStatus.PAYMENT_COMPLETED,
                payment.getPaidAt(),
                PaymentStatus.COMPLETED,
                null,
                null,
                0
        );
    }
}
