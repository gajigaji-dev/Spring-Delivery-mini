package com.sparta.springdeliverymini.entity;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import jakarta.persistence.*;

// 이월 차감 기록 — 정산 A에서 다 차감하지 못한 금액(carriedOutAmount)을 정산 B에서 차감했다는 기록 (docs/settlement-policy.md 9장)
//
// - from_settlement_id UNIQUE (V9) → 한 정산의 이월분은 정확히 한 번만 차감
// - 아직 이 기록이 없는 이월분 = 미차감 이월 → 다음에 새로 집계되는 "더 뒤 날짜"의 정산이 가져감
//   날짜 역순으로 집계돼도(D-1을 D보다 늦게 집계) 이월분이 앞 날짜로 거슬러 가지 않음
// - 기존 정산 행은 수정하지 않고 이 기록만 INSERT → 이미 지급된 정산은 바뀌지 않음
@Entity
@Table(name = "settlement_carryovers")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SettlementCarryover extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // 이월분을 넘긴 정산
    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "from_settlement_id", nullable = false, unique = true)
    private Settlement fromSettlement;

    // 이월분을 차감한 정산
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "to_settlement_id", nullable = false)
    private Settlement toSettlement;

    // 차감한 금액 = fromSettlement.carriedOutAmount
    @Column(nullable = false)
    private long amount;

    // 규칙 위반은 집계 코드의 오류이므로 IllegalArgumentException
    public SettlementCarryover(Settlement from, Settlement to) {
        if (from == null || to == null || from == to) {
            throw new IllegalArgumentException("서로 다른 두 정산이 필요합니다.");
        }
        if (from.getCarriedOutAmount() <= 0) {
            throw new IllegalArgumentException("이월할 금액이 없는 정산입니다.");
        }
        if (!from.getSettlementDate().isBefore(to.getSettlementDate())) {
            throw new IllegalArgumentException("이월분은 더 뒤 날짜의 정산에서만 차감할 수 있습니다.");
        }
        if (!SettlementRefund.sameStore(from.getStore(), to.getStore())) {
            throw new IllegalArgumentException("같은 매장의 정산끼리만 이월할 수 있습니다.");
        }
        this.fromSettlement = from;
        this.toSettlement = to;
        this.amount = from.getCarriedOutAmount();
    }
}
