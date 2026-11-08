package com.sparta.springdeliverymini.entity;

import java.util.List;

// 정산 대상 결제들의 합계 (값 객체)
//
// - 정산 조회(StoreSettlementResponse)와 정산 확정(Settlement)이 같은 계산을 쓰도록 한곳에 둠
//   → 조회 API로 본 금액과 확정되어 저장된 금액이 항상 같음
// - 결제마다 저장된 금액을 더하기만 함 (다시 반올림하지 않음)
//   → 건별로 수수료 + 정산액 = 결제액이므로 합계에서도 totalFee + settlementAmount = totalAmount
// - long 범위를 넘으면 잘못된 합계를 내보내지 않도록 ArithmeticException
public record SettlementTotals(int paymentCount, long totalAmount, long totalFee, long settlementAmount) {

    public static SettlementTotals of(List<Payment> payments) {
        long totalAmount = 0;
        long totalFee = 0;
        long settlementAmount = 0;
        for (Payment payment : payments) {
            totalAmount = Math.addExact(totalAmount, payment.getAmount());
            totalFee = Math.addExact(totalFee, payment.getFeeAmount());
            settlementAmount = Math.addExact(settlementAmount, payment.getSettlementAmount());
        }
        return new SettlementTotals(payments.size(), totalAmount, totalFee, settlementAmount);
    }
}
