package com.sparta.springdeliverymini.entity;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import jakarta.persistence.*;

import java.time.LocalDate;
import java.time.LocalDateTime;

// 매장별 일 정산 확정 기록 — 정산 기준은 docs/settlement-policy.md
//
// - 매장 1곳의 하루(settlementDate, 배달완료 시각 기준 [D 00:00, D+1 00:00)) 정산 결과를 확정해 저장
// - 같은 매장·같은 날짜는 한 번만 확정 → (store_id, settlement_date) UNIQUE (V7)
//   스케줄러가 여러 번 돌거나 여러 서버에서 동시에 돌아도 DB가 중복 저장을 막음
// - 금액은 확정 시점에 고정 → 이후 바뀌지 않음 (setter 없음, 상태 변경은 markPaid()만)
// BaseEntity 상속 → created_at, updated_at 자동 기록
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

    // 정산 대상 결제 건수
    @Column(nullable = false)
    private int paymentCount;

    // 결제 총액
    @Column(nullable = false)
    private long totalAmount;

    // 플랫폼 수수료 합계
    @Column(nullable = false)
    private long totalFee;

    // 사장님 정산액 합계 (= totalAmount - totalFee)
    @Column(nullable = false)
    private long settlementAmount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private SettlementStatus status;

    // 확정 시각 — 집계해 금액을 고정한 시각
    @Column(nullable = false)
    private LocalDateTime confirmedAt;

    // 지급 시각 — PAID일 때만 있음 (DB CHECK 제약, V7)
    private LocalDateTime paidAt;

    private Settlement(Store store, LocalDate settlementDate, SettlementTotals totals, LocalDateTime confirmedAt) {
        this.store = store;
        this.settlementDate = settlementDate;
        this.paymentCount = totals.paymentCount();
        this.totalAmount = totals.totalAmount();
        this.totalFee = totals.totalFee();
        this.settlementAmount = totals.settlementAmount();
        this.status = SettlementStatus.CONFIRMED;
        this.confirmedAt = confirmedAt;
    }

    // 하루치 정산 확정
    // confirmedAt은 호출하는 쪽(SettlementConfirmService)이 Clock 기준 현재 시각을 넘김
    // 확정 시각(D+1 01:00) 전에는 그날 배달완료가 아직 들어올 수 있으므로 거부 → 확정된 금액이 나중에 달라지지 않음
    public static Settlement confirm(Store store, LocalDate settlementDate, SettlementTotals totals,
                                     LocalDateTime confirmedAt) {
        if (store == null || settlementDate == null || totals == null || confirmedAt == null) {
            throw new IllegalArgumentException("정산 확정에 필요한 값이 없습니다.");
        }
        LocalDate lastConfirmed = SettlementPeriod.lastConfirmedDate(confirmedAt);
        if (settlementDate.isAfter(lastConfirmed)) {
            throw new IllegalArgumentException(
                    "아직 확정할 수 없는 날짜입니다: " + settlementDate + " (" + lastConfirmed + "까지 확정 가능)");
        }
        return new Settlement(store, settlementDate, totals, confirmedAt);
    }

    // 지급 완료 처리 — CONFIRMED에서만 가능 (이미 지급된 정산을 다시 지급하지 않음)
    public void markPaid(LocalDateTime paidAt) {
        if (paidAt == null) {
            throw new IllegalArgumentException("지급 시각이 필요합니다.");
        }
        if (status != SettlementStatus.CONFIRMED) {
            throw new IllegalStateException("확정 상태의 정산만 지급 처리할 수 있습니다. 현재 상태: " + status);
        }
        this.status = SettlementStatus.PAID;
        this.paidAt = paidAt;
    }
}
