package com.sparta.springdeliverymini.entity;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import jakarta.persistence.*;

// 환불 차감 기록 — 환불 1건이 어느 정산에서 차감됐는지 (docs/settlement-policy.md 9장)
//
// - payment_id UNIQUE (V9) → 환불 1건은 정확히 한 정산에서만 차감 (락이 빠지거나 동시에 집계돼도 DB가 중복 차감을 거부)
// - 아직 이 기록이 없는 환불 = 미차감 환불 → 다음에 새로 집계되는 정산이 가져감
//   환불일 정산이 이미 만들어진 뒤에 커밋된 환불도 누락되지 않음
// - 금액은 환불된 결제의 금액 스냅샷 (결제 금액은 바뀌지 않지만 정산 기록만으로 합계를 검증할 수 있도록 함께 저장)
@Entity
@Table(name = "settlement_refunds")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SettlementRefund extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // 환불을 차감한 정산
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "settlement_id", nullable = false)
    private Settlement settlement;

    // 환불된 결제
    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "payment_id", nullable = false, unique = true)
    private Payment payment;

    @Column(nullable = false)
    private long amount;

    @Column(nullable = false)
    private long feeAmount;

    @Column(nullable = false)
    private long settlementAmount;

    // 규칙 위반은 집계 코드의 오류이므로 IllegalArgumentException
    // - 환불된 결제만
    // - 환불 시각이 정산 날짜 구간의 끝보다 앞서야 함 → 환불보다 이른 날짜의 정산에서 차감되지 않음
    // - 같은 매장의 결제만
    public SettlementRefund(Settlement settlement, Payment payment) {
        if (settlement == null || payment == null) {
            throw new IllegalArgumentException("정산과 결제가 필요합니다.");
        }
        if (payment.getStatus() != PaymentStatus.REFUNDED) {
            throw new IllegalArgumentException("환불된 결제만 정산에서 차감할 수 있습니다. 현재 상태: " + payment.getStatus());
        }
        if (!payment.getRefundedAt().isBefore(settlement.periodEnd())) {
            throw new IllegalArgumentException("환불 시각 이후 날짜의 정산에서만 차감할 수 있습니다.");
        }
        if (!sameStore(payment.getOrder().getStore(), settlement.getStore())) {
            throw new IllegalArgumentException("같은 매장의 결제만 정산에서 차감할 수 있습니다.");
        }
        this.settlement = settlement;
        this.payment = payment;
        this.amount = payment.getAmount();
        this.feeAmount = payment.getFeeAmount();
        this.settlementAmount = payment.getSettlementAmount();
    }

    // 저장된 매장은 id로, 저장 전(id 없음)이면 같은 객체인지로 판단
    static boolean sameStore(Store a, Store b) {
        if (a == b) {
            return true;
        }
        return a != null && b != null && a.getId() != null && a.getId().equals(b.getId());
    }
}
