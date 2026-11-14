package com.sparta.springdeliverymini.entity;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import jakarta.persistence.*;

import java.time.LocalDateTime;

// 정산 상태 변경 이력 (감사 기록, V11) — 누가 언제 어떤 상태로 바꿨는지 (docs/settlement-policy.md 10장)
//
// - 집계(→ PENDING), 확정(PENDING → CONFIRMED), 지급(CONFIRMED → PAID)마다 1건씩 INSERT
//   상태 변경과 같은 트랜잭션에서 저장 → 상태만 바뀌고 이력이 빠지는 일 없음
// - 한 번 저장하면 바꾸지 않음 (모든 컬럼 updatable = false, setter 없음)
// - 처리자(actor)는 요청 값이 아니라 인증된 관리자(DB에서 조회한 User) — 호출하는 Service가 넘김
//   스케줄러가 집계한 경우에만 null (DB CHECK: 확정·지급 이력은 처리자 필수)
// - (settlement_id, to_status) UNIQUE → 같은 전이가 두 번 기록되지 않음
// - BaseEntity를 상속하지 않음: 수정되지 않으므로 updated_at이 필요 없고,
//   시각은 Auditing이 아니라 상태 전이에 쓴 시각(confirmedAt, paidAt)과 같은 값을 직접 받음
@Entity
@Table(name = "settlement_status_histories",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_settlement_status_histories_settlement_to", columnNames = {"settlement_id", "to_status"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SettlementStatusHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "settlement_id", nullable = false, updatable = false)
    private Settlement settlement;

    // 이전 상태 — 집계로 처음 만들어진 경우 null
    @Enumerated(EnumType.STRING)
    @Column(length = 20, updatable = false)
    private SettlementStatus fromStatus;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20, updatable = false)
    private SettlementStatus toStatus;

    // 처리자 — 스케줄러 집계면 null
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "actor_user_id", updatable = false)
    private User actor;

    @Column(nullable = false, updatable = false)
    private LocalDateTime changedAt;

    private SettlementStatusHistory(Settlement settlement, SettlementStatus fromStatus, SettlementStatus toStatus,
                                    User actor, LocalDateTime changedAt) {
        if (settlement == null || changedAt == null) {
            throw new IllegalArgumentException("정산 이력에 필요한 값이 없습니다.");
        }
        // 이력은 상태 전이 직후에 만든다 → 정산의 현재 상태와 다르면 호출 순서가 잘못된 것
        if (settlement.getStatus() != toStatus) {
            throw new IllegalArgumentException(
                    "정산 상태와 이력이 맞지 않습니다. 정산: " + settlement.getStatus() + ", 이력: " + toStatus);
        }
        this.settlement = settlement;
        this.fromStatus = fromStatus;
        this.toStatus = toStatus;
        this.actor = actor;
        this.changedAt = changedAt;
    }

    // 집계로 정산이 만들어짐 — actor: 수동 재집계한 관리자, 스케줄러면 null
    public static SettlementStatusHistory aggregated(Settlement settlement, User actor, LocalDateTime aggregatedAt) {
        return new SettlementStatusHistory(settlement, null, SettlementStatus.PENDING, actor, aggregatedAt);
    }

    // 관리자 확정 — settlement.confirm() 직후 호출
    public static SettlementStatusHistory confirmed(Settlement settlement, User admin) {
        requireActor(admin);
        return new SettlementStatusHistory(settlement, SettlementStatus.PENDING, SettlementStatus.CONFIRMED,
                admin, settlement.getConfirmedAt());
    }

    // 관리자 지급 — settlement.markPaid() 직후 호출
    public static SettlementStatusHistory paid(Settlement settlement, User admin) {
        requireActor(admin);
        return new SettlementStatusHistory(settlement, SettlementStatus.CONFIRMED, SettlementStatus.PAID,
                admin, settlement.getPaidAt());
    }

    private static void requireActor(User actor) {
        if (actor == null) {
            throw new IllegalArgumentException("확정·지급 이력에는 처리자가 필요합니다.");
        }
    }
}
