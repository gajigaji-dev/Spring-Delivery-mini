package com.sparta.springdeliverymini.entity;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import com.sparta.springdeliverymini.exception.InvalidSettlementStatusException;
import jakarta.persistence.*;

import java.time.LocalDate;
import java.time.LocalDateTime;

// 매장별 일 정산 기록 — 정산 기준은 docs/settlement-policy.md
//
// - 매장 1곳의 하루(settlementDate, 배달완료 시각 기준 [D 00:00, D+1 00:00)) 정산 결과
// - 같은 매장·같은 날짜는 한 건만 → (store_id, settlement_date) UNIQUE (V7)
//   스케줄러가 여러 번 돌거나 여러 서버에서 동시에 돌아도 DB가 중복 저장을 막음
// - 상태: PENDING(집계, 확정 대기) → CONFIRMED(관리자 확정) → PAID(지급완료)
//   전이 규칙은 이 클래스의 메서드에서만 판단, 위반 시 InvalidSettlementStatusException (→ 409)
// - 금액은 집계 시점에 고정 → 이후 바뀌지 않음 (setter 없음). 나중에 생긴 환불·이월은 다음에 새로 집계되는 정산에서 차감
//
// 금액 구성 (V9, docs/settlement-policy.md 9장)
//   판매: paymentCount, totalAmount, totalFee, salesSettlementAmount — 이날 배달완료된 결제
//   환불: refundCount, refundAmount, refundFee, refundSettlementAmount — 이 정산에서 차감한 환불 (SettlementRefund)
//   이월받은 금액: carriedInAmount — 이전 정산에서 넘어온 미차감액 (SettlementCarryover)
//   차감 전 정산액 = 판매 정산액 - 환불 정산액 - 이월받은 금액
//   지급액(settlementAmount) = max(차감 전 정산액, 0), 다음 이월액(carriedOutAmount) = max(-차감 전 정산액, 0)
// BaseEntity 상속 → created_at(집계 시각), updated_at(마지막 상태 변경 시각) 자동 기록
@Entity
@Table(name = "settlements",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_settlements_store_date", columnNames = {"store_id", "settlement_date"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED) // JPA용 기본 생성자, 외부에서 new 금지
public class Settlement extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // 정산 대상 매장
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "store_id", nullable = false)
    private Store store;

    // 정산 날짜 (Asia/Seoul) — 배달완료 시각이 이 날짜에 속한 결제를 정산
    @Column(nullable = false)
    private LocalDate settlementDate;

    // ===== 판매 =====
    // 정산 대상 결제 건수
    @Column(nullable = false)
    private int paymentCount;

    // 결제 총액
    @Column(nullable = false)
    private long totalAmount;

    // 플랫폼 수수료 합계
    @Column(nullable = false)
    private long totalFee;

    // 판매 정산액 합계 (= totalAmount - totalFee)
    @Column(nullable = false)
    private long salesSettlementAmount;

    // ===== 환불 (이 정산에서 차감) =====
    @Column(nullable = false)
    private int refundCount;

    // 환불 총액 (고객에게 돌려준 결제액 합계)
    @Column(nullable = false)
    private long refundAmount;

    // 취소된 플랫폼 수수료 합계
    @Column(nullable = false)
    private long refundFee;

    // 차감한 매장 정산액 합계 (= refundAmount - refundFee)
    @Column(nullable = false)
    private long refundSettlementAmount;

    // ===== 이월 · 지급 =====
    // 이전 정산에서 이월받아 이번에 차감한 금액
    @Column(nullable = false)
    private long carriedInAmount;

    // 이번 정산에서 다 차감하지 못해 다음 정산으로 넘기는 금액 (> 0이면 지급액은 0)
    @Column(nullable = false)
    private long carriedOutAmount;

    // 실제 지급액 (≥ 0)
    @Column(nullable = false)
    private long settlementAmount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private SettlementStatus status;

    // 확정 시각 — 관리자가 확정한 시각. PENDING이면 없음 (DB CHECK 제약, V8)
    private LocalDateTime confirmedAt;

    // 지급 시각 — PAID일 때만 있음 (DB CHECK 제약, V7)
    private LocalDateTime paidAt;

    private Settlement(Store store, LocalDate settlementDate, SettlementTotals sales, SettlementTotals refunds,
                       long carriedInAmount) {
        this.store = store;
        this.settlementDate = settlementDate;
        this.paymentCount = sales.paymentCount();
        this.totalAmount = sales.totalAmount();
        this.totalFee = sales.totalFee();
        this.salesSettlementAmount = sales.settlementAmount();
        this.refundCount = refunds.paymentCount();
        this.refundAmount = refunds.totalAmount();
        this.refundFee = refunds.totalFee();
        this.refundSettlementAmount = refunds.settlementAmount();
        this.carriedInAmount = carriedInAmount;

        // long 범위를 넘으면 잘못된 금액을 저장하지 않도록 ArithmeticException
        long net = Math.subtractExact(Math.subtractExact(salesSettlementAmount, refundSettlementAmount), carriedInAmount);
        this.settlementAmount = Math.max(net, 0);
        this.carriedOutAmount = net < 0 ? Math.negateExact(net) : 0;
        this.status = SettlementStatus.PENDING;
    }

    // 판매만 있는 하루치 정산 (환불·이월 없음)
    public static Settlement aggregate(Store store, LocalDate settlementDate, SettlementTotals sales,
                                       LocalDateTime aggregatedAt) {
        return aggregate(store, settlementDate, sales, SettlementTotals.EMPTY, 0, aggregatedAt);
    }

    // 하루치 정산 집계 → PENDING 상태로 생성
    // - sales: 이날 배달완료된 결제 합계, refunds: 이 정산에서 차감할 환불 합계, carriedInAmount: 이월받은 금액
    // - aggregatedAt은 호출하는 쪽(SettlementAggregationService)이 Clock 기준 현재 시각을 넘김
    // - 집계 가능 시각(D+1 01:00) 전에는 그날 배달완료가 아직 들어올 수 있으므로 거부 → 집계한 금액이 나중에 달라지지 않음
    public static Settlement aggregate(Store store, LocalDate settlementDate, SettlementTotals sales,
                                       SettlementTotals refunds, long carriedInAmount, LocalDateTime aggregatedAt) {
        if (store == null || settlementDate == null || sales == null || refunds == null || aggregatedAt == null) {
            throw new IllegalArgumentException("정산 집계에 필요한 값이 없습니다.");
        }
        if (carriedInAmount < 0) {
            throw new IllegalArgumentException("이월받은 금액은 0 이상이어야 합니다: " + carriedInAmount);
        }
        LocalDate lastClosed = SettlementPeriod.lastConfirmedDate(aggregatedAt);
        if (settlementDate.isAfter(lastClosed)) {
            throw new IllegalArgumentException(
                    "아직 집계할 수 없는 날짜입니다: " + settlementDate + " (" + lastClosed + "까지 집계 가능)");
        }
        return new Settlement(store, settlementDate, sales, refunds, carriedInAmount);
    }

    // 정산 날짜 구간의 끝 (미포함) — 이 시각 전에 환불된 미차감 환불까지 이 정산에서 차감할 수 있음
    public LocalDateTime periodEnd() {
        return settlementDate.plusDays(1).atStartOfDay();
    }

    // 정산 확정 — PENDING에서만 가능
    // confirmedAt은 호출하는 쪽(AdminSettlementService)이 Clock 기준 현재 시각을 넘김
    public void confirm(LocalDateTime confirmedAt) {
        if (confirmedAt == null) {
            throw new IllegalArgumentException("확정 시각이 필요합니다.");
        }
        if (status != SettlementStatus.PENDING) {
            throw new InvalidSettlementStatusException(
                    "확정 대기(PENDING) 상태의 정산만 확정할 수 있습니다. 현재 상태: " + status);
        }
        this.status = SettlementStatus.CONFIRMED;
        this.confirmedAt = confirmedAt;
    }

    // 지급 처리 — CONFIRMED에서만 가능
    // 확정 전 정산은 지급할 수 없고, 이미 지급된 정산은 다시 지급하지 않음 (지급 시각도 처음 값 유지)
    public void markPaid(LocalDateTime paidAt) {
        if (paidAt == null) {
            throw new IllegalArgumentException("지급 시각이 필요합니다.");
        }
        if (status != SettlementStatus.CONFIRMED) {
            throw new InvalidSettlementStatusException(
                    "확정(CONFIRMED) 상태의 정산만 지급할 수 있습니다. 현재 상태: " + status);
        }
        if (paidAt.isBefore(confirmedAt)) {
            throw new IllegalArgumentException("지급 시각은 확정 시각보다 앞설 수 없습니다.");
        }
        this.status = SettlementStatus.PAID;
        this.paidAt = paidAt;
    }
}
