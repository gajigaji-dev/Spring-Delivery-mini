package com.sparta.springdeliverymini.dto;

import com.sparta.springdeliverymini.entity.Payment;

import java.math.BigDecimal;
import java.time.LocalDateTime;

// 정산 건별 내역 (OWNER 전용)
// 수수료·정산액은 사장님/플랫폼 내부 정보 → CUSTOMER가 받는 PaymentResponse와 분리
public record SettlementItemResponse(
        Long paymentId,
        Long orderId,
        long amount,              // 결제 금액
        BigDecimal feeRate,       // 결제 시점 수수료율 스냅샷
        long feeAmount,           // 플랫폼 수수료
        long settlementAmount,    // 사장님 정산액 (amount - feeAmount)
        LocalDateTime paidAt,     // 결제 시각
        LocalDateTime deliveredAt) { // 배달완료 시각 (정산 기간 기준)

    public static SettlementItemResponse from(Payment payment) {
        return new SettlementItemResponse(
                payment.getId(),
                payment.getOrder().getId(),
                payment.getAmount(),
                payment.getFeeRate(),
                payment.getFeeAmount(),
                payment.getSettlementAmount(),
                payment.getPaidAt(),
                payment.getOrder().getDeliveredAt()
        );
    }
}
