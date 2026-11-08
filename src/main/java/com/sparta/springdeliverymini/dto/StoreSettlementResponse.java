package com.sparta.springdeliverymini.dto;

import com.sparta.springdeliverymini.entity.Payment;
import com.sparta.springdeliverymini.entity.SettlementPeriod;
import com.sparta.springdeliverymini.entity.Store;

import java.time.LocalDate;
import java.util.List;

// 매장별 기간 정산 (OWNER 전용) — 기간 합계 + 건별 내역
public record StoreSettlementResponse(
        Long storeId,
        String storeName,
        LocalDate from,           // 조회 시작일 (포함)
        LocalDate to,             // 조회 종료일 (포함)
        int paymentCount,         // 정산 대상 결제 건수
        long totalAmount,         // 결제 총액
        long totalFee,            // 플랫폼 수수료 합계
        long settlementAmount,    // 사장님 정산액 합계 (= totalAmount - totalFee)
        List<SettlementItemResponse> items) { // 배달완료 시각 순

    // 합계는 결제마다 저장된 금액을 더하기만 함 (다시 반올림하지 않음)
    // → 건별로 수수료 + 정산액 = 결제액이므로 합계에서도 totalFee + settlementAmount = totalAmount
    // long 범위를 넘으면 잘못된 합계를 내보내지 않도록 ArithmeticException
    public static StoreSettlementResponse of(Store store, SettlementPeriod period, List<Payment> payments) {
        long totalAmount = 0;
        long totalFee = 0;
        long settlementAmount = 0;
        for (Payment payment : payments) {
            totalAmount = Math.addExact(totalAmount, payment.getAmount());
            totalFee = Math.addExact(totalFee, payment.getFeeAmount());
            settlementAmount = Math.addExact(settlementAmount, payment.getSettlementAmount());
        }

        return new StoreSettlementResponse(
                store.getId(),
                store.getName(),
                period.from(),
                period.to(),
                payments.size(),
                totalAmount,
                totalFee,
                settlementAmount,
                payments.stream().map(SettlementItemResponse::from).toList()
        );
    }
}
