package com.sparta.springdeliverymini.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

// 환불 1건 (V21) — 결제 1 : 환불 N. 부분 환불·전액 환불 모두 이 행 하나가 정산 차감 단위
//
// - 생성 후 바뀌지 않음(결제·정산 기록 불변 규칙): 금액을 고칠 일이 생기면 새 환불 행으로
// - 금액은 Payment.refund가 PaymentFee.refundPortion으로 계산해 넘김 → 수수료 + 정산액 = 환불액 (DB CHECK)
// - 같은 결제의 환불 합계는 결제 금액을 넘지 않음: 결제 행 잠금 + payments.refunded_amount CHECK
// - 정산에서 차감됐는지는 settlement_refunds.payment_refund_id(UNIQUE)로 판단 → 환불 1건은 정확히 한 정산에서만 차감
@Entity
@Table(name = "payment_refunds")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PaymentRefund extends BaseEntity {

    private static final int MAX_REASON_LENGTH = 200;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "payment_id", nullable = false, updatable = false)
    private Payment payment;

    // 이번 환불 금액 (고객에게 돌려주는 돈)
    @Column(nullable = false, updatable = false)
    private long amount;

    // 함께 취소되는 플랫폼 수수료
    @Column(nullable = false, updatable = false)
    private long feeAmount;

    // 매장 정산에서 차감되는 금액 (= amount - feeAmount)
    @Column(nullable = false, updatable = false)
    private long settlementAmount;

    @Column(nullable = false, length = 200, updatable = false)
    private String reason;

    // 정산 차감 기준 시각 — 이 시각 이후에 집계되는 정산 중 처음 것에서 차감 (docs/settlement-policy.md 9장)
    @Column(nullable = false, updatable = false)
    private LocalDateTime refundedAt;

    PaymentRefund(Payment payment, long amount, long feeAmount, long settlementAmount, String reason,
                  LocalDateTime refundedAt) {
        if (payment == null || refundedAt == null) {
            throw new IllegalArgumentException("환불에는 결제와 환불 시각이 필요합니다.");
        }
        if (amount <= 0 || feeAmount < 0 || settlementAmount < 0 || feeAmount + settlementAmount != amount) {
            throw new IllegalArgumentException("환불 금액이 올바르지 않습니다: " + amount + " = " + feeAmount + " + "
                    + settlementAmount);
        }
        String trimmed = reason == null ? "" : reason.strip();
        this.payment = payment;
        this.amount = amount;
        this.feeAmount = feeAmount;
        this.settlementAmount = settlementAmount;
        this.reason = trimmed.isEmpty() ? "환불" : trimmed.substring(0, Math.min(trimmed.length(), MAX_REASON_LENGTH));
        this.refundedAt = refundedAt;
    }
}
