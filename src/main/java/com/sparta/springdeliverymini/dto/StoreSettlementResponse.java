package com.sparta.springdeliverymini.dto;

import com.sparta.springdeliverymini.entity.Payment;
import com.sparta.springdeliverymini.entity.SettlementPeriod;
import com.sparta.springdeliverymini.entity.SettlementTotals;
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

    // 합계 계산은 SettlementTotals — 정산 확정(Settlement)과 같은 계산을 사용
    public static StoreSettlementResponse of(Store store, SettlementPeriod period, List<Payment> payments) {
        SettlementTotals totals = SettlementTotals.of(payments);

        return new StoreSettlementResponse(
                store.getId(),
                store.getName(),
                period.from(),
                period.to(),
                totals.paymentCount(),
                totals.totalAmount(),
                totals.totalFee(),
                totals.settlementAmount(),
                payments.stream().map(SettlementItemResponse::from).toList()
        );
    }
}
