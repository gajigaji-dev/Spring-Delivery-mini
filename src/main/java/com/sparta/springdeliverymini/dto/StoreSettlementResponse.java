package com.sparta.springdeliverymini.dto;

import com.sparta.springdeliverymini.entity.Payment;
import com.sparta.springdeliverymini.entity.SettlementPeriod;
import com.sparta.springdeliverymini.entity.SettlementTotals;
import com.sparta.springdeliverymini.entity.Store;

import java.time.LocalDate;
import java.util.List;

// 매장별 기간 정산 (OWNER 전용) — 기간 합계 + 건별 내역
// - 판매: 배달완료 시각이 기간 안인 결제 (나중에 환불돼도 판매 내역에서 빠지지 않음 → 조회한 금액이 바뀌지 않음)
// - 환불: 환불 시각이 기간 안인 결제 (환불이 발생한 날 기준)
// - 이월·실제 지급액은 일 정산 기록(관리자 정산 API) 기준이라 여기에는 없음 (docs/settlement-policy.md 9장)
public record StoreSettlementResponse(
        Long storeId,
        String storeName,
        LocalDate from,           // 조회 시작일 (포함)
        LocalDate to,             // 조회 종료일 (포함)
        int paymentCount,         // 판매 건수
        long totalAmount,         // 판매 결제 총액
        long totalFee,            // 판매 플랫폼 수수료 합계
        long settlementAmount,    // 판매 정산액 합계 (= totalAmount - totalFee)
        List<SettlementItemResponse> items, // 판매 건별 내역, 배달완료 시각 순
        int refundCount,          // 환불 건수
        long refundAmount,        // 환불 총액
        long refundFee,           // 취소된 플랫폼 수수료 합계
        long refundSettlementAmount, // 차감되는 매장 정산액 합계
        long netSettlementAmount, // 순 정산액 = 판매 정산액 - 환불 정산액 (음수 가능)
        List<RefundItemResponse> refunds) { // 환불 건별 내역, 환불 시각 순

    // 합계 계산은 SettlementTotals — 정산 집계(Settlement)와 같은 계산을 사용
    public static StoreSettlementResponse of(Store store, SettlementPeriod period,
                                             List<Payment> sales, List<Payment> refunds) {
        SettlementTotals salesTotals = SettlementTotals.of(sales);
        SettlementTotals refundTotals = SettlementTotals.of(refunds);

        return new StoreSettlementResponse(
                store.getId(),
                store.getName(),
                period.from(),
                period.to(),
                salesTotals.paymentCount(),
                salesTotals.totalAmount(),
                salesTotals.totalFee(),
                salesTotals.settlementAmount(),
                sales.stream().map(SettlementItemResponse::from).toList(),
                refundTotals.paymentCount(),
                refundTotals.totalAmount(),
                refundTotals.totalFee(),
                refundTotals.settlementAmount(),
                Math.subtractExact(salesTotals.settlementAmount(), refundTotals.settlementAmount()),
                refunds.stream().map(RefundItemResponse::from).toList()
        );
    }
}
