package com.sparta.springdeliverymini.dto;

import com.sparta.springdeliverymini.entity.Settlement;
import com.sparta.springdeliverymini.entity.SettlementCarryover;
import com.sparta.springdeliverymini.entity.SettlementRefund;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

// 일 정산 상세 (OWNER 전용) — 정산 1건이 어떤 판매·환불·이월로 구성됐는지
// - sales: 그날 배달완료된 결제 (나중에 환불돼도 포함 → 정산의 판매 합계와 항상 같음)
// - refundDeductions: 이 정산에서 실제로 차감한 환불 (settlement_refunds, 환불한 날짜가 아니라 차감된 정산 기준)
// - carriedIn: 이전 정산에서 넘어와 이 정산에서 차감한 이월분
// - carriedOut: 이 정산이 다음으로 넘긴 이월분 (없으면 null), 어느 정산에서 차감됐는지 또는 아직 차감 전인지
public record OwnerSettlementDetailResponse(
        Long storeId,
        String storeName,
        OwnerSettlementResponse settlement,
        List<SettlementItemResponse> sales,
        List<RefundDeduction> refundDeductions,
        List<CarriedIn> carriedIn,
        CarriedOut carriedOut) {

    // 차감한 환불 1건 — 금액은 차감 시점 스냅샷(settlement_refunds)
    public record RefundDeduction(
            Long paymentId,
            Long orderId,
            long amount,
            long feeAmount,
            long settlementAmount,
            LocalDateTime deliveredAt,
            LocalDateTime refundedAt) {

        static RefundDeduction from(SettlementRefund r) {
            return new RefundDeduction(
                    r.getPayment().getId(),
                    r.getPayment().getOrder().getId(),
                    r.getAmount(),
                    r.getFeeAmount(),
                    r.getSettlementAmount(),
                    r.getPayment().getOrder().getDeliveredAt(),
                    r.getPayment().getRefundedAt());
        }
    }

    // 이월받은 금액 1건 — 원래 정산
    public record CarriedIn(Long fromSettlementId, LocalDate fromSettlementDate, long amount) {

        static CarriedIn from(SettlementCarryover c) {
            return new CarriedIn(c.getFromSettlement().getId(), c.getFromSettlement().getSettlementDate(), c.getAmount());
        }
    }

    // 넘긴 이월분 — deducted가 false면 아직 다음 판매·환불이 없어 차감 전 (toSettlement* = null)
    public record CarriedOut(long amount, boolean deducted, Long toSettlementId, LocalDate toSettlementDate) {
    }

    public static OwnerSettlementDetailResponse of(Settlement settlement,
                                                   List<SettlementItemResponse> sales,
                                                   List<SettlementRefund> refunds,
                                                   List<SettlementCarryover> carriedIn,
                                                   SettlementCarryover carriedOut) {
        CarriedOut out = null;
        if (settlement.getCarriedOutAmount() > 0) {
            out = carriedOut == null
                    ? new CarriedOut(settlement.getCarriedOutAmount(), false, null, null)
                    : new CarriedOut(carriedOut.getAmount(), true,
                    carriedOut.getToSettlement().getId(), carriedOut.getToSettlement().getSettlementDate());
        }
        return new OwnerSettlementDetailResponse(
                settlement.getStore().getId(),
                settlement.getStore().getName(),
                OwnerSettlementResponse.from(settlement),
                sales,
                refunds.stream().map(RefundDeduction::from).toList(),
                carriedIn.stream().map(CarriedIn::from).toList(),
                out);
    }
}
