package com.sparta.springdeliverymini.entity;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import jakarta.persistence.*;

import java.time.LocalDate;
import java.time.LocalDateTime;

// 정산 집계 실패 기록 (V11) — 매장·날짜당 1건 (docs/settlement-policy.md 10장)
//
// - 집계가 실패하면 그 매장 트랜잭션은 전부 롤백되어 정산이 남지 않음 → 로그만으로는 어떤 매장·날짜가 빠졌는지 찾기 어려움
//   실패를 별도 트랜잭션으로 이 테이블에 남겨 관리자가 조회하고 재집계할 수 있게 함
// - 상태: FAILED(정산 없음, 재집계 대상) → RESOLVED(이후 집계 성공 또는 대상 없음 확인)
//   다시 실패하면 같은 행을 FAILED로 되돌리고 횟수만 늘림
// - 정산 금액과 무관한 운영 기록이라 UPDATE함 (정산·결제 기록의 불변 규칙 대상 아님)
// - 이 행을 쓰는 코드는 모두 매장 행 잠금을 잡은 상태 (SettlementAggregationService) → 동시 갱신 없음
// - 처리자(lastFailedBy, resolvedBy): 수동 재집계한 관리자, 스케줄러면 null
@Entity
@Table(name = "settlement_aggregation_failures",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_settlement_aggregation_failures_store_date", columnNames = {"store_id", "settlement_date"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SettlementAggregationFailure {

    static final int MAX_ERROR_LENGTH = 500;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "store_id", nullable = false, updatable = false)
    private Store store;

    @Column(nullable = false, updatable = false)
    private LocalDate settlementDate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private SettlementAggregationFailureStatus status;

    // 지금까지 실패한 횟수
    @Column(nullable = false)
    private int failureCount;

    // 마지막 실패 원인 요약 (예외 종류·제약 조건 이름 — 입력값이 섞일 수 있는 DB 오류 상세는 담지 않음)
    @Column(nullable = false, length = MAX_ERROR_LENGTH)
    private String lastError;

    @Column(nullable = false, updatable = false)
    private LocalDateTime firstFailedAt;

    @Column(nullable = false)
    private LocalDateTime lastFailedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "last_failed_by_user_id")
    private User lastFailedBy;

    // RESOLVED일 때만 있음 (DB CHECK)
    private LocalDateTime resolvedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "resolved_by_user_id")
    private User resolvedBy;

    // 재집계로 만들어진 정산 — 집계할 대상이 없어 해결된 경우 null
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "settlement_id")
    private Settlement settlement;

    public SettlementAggregationFailure(Store store, LocalDate settlementDate, String error, User actor,
                                        LocalDateTime failedAt) {
        if (store == null || settlementDate == null || failedAt == null) {
            throw new IllegalArgumentException("집계 실패 기록에 필요한 값이 없습니다.");
        }
        this.store = store;
        this.settlementDate = settlementDate;
        this.status = SettlementAggregationFailureStatus.FAILED;
        this.failureCount = 1;
        this.lastError = truncate(error);
        this.firstFailedAt = failedAt;
        this.lastFailedAt = failedAt;
        this.lastFailedBy = actor;
    }

    public boolean isFailed() {
        return status == SettlementAggregationFailureStatus.FAILED;
    }

    // 다시 실패 — 해결됐던 기록이면 FAILED로 되돌림 (예: 대상 없음으로 해결된 뒤 새 환불이 생겨 다시 실패)
    public void recordFailure(String error, User actor, LocalDateTime failedAt) {
        if (failedAt == null) {
            throw new IllegalArgumentException("실패 시각이 필요합니다.");
        }
        this.status = SettlementAggregationFailureStatus.FAILED;
        this.failureCount = Math.addExact(failureCount, 1);
        this.lastError = truncate(error);
        this.lastFailedAt = failedAt;
        this.lastFailedBy = actor;
        this.resolvedAt = null;
        this.resolvedBy = null;
        this.settlement = null;
    }

    // 해결 — FAILED에서만 가능. settlement: 만들어진 정산 (대상 없음이면 null)
    public void resolve(Settlement settlement, User actor, LocalDateTime resolvedAt) {
        if (resolvedAt == null) {
            throw new IllegalArgumentException("해결 시각이 필요합니다.");
        }
        if (!isFailed()) {
            throw new IllegalStateException("실패(FAILED) 상태의 기록만 해결할 수 있습니다. 현재 상태: " + status);
        }
        this.status = SettlementAggregationFailureStatus.RESOLVED;
        this.resolvedAt = resolvedAt;
        this.resolvedBy = actor;
        this.settlement = settlement;
    }

    private static String truncate(String error) {
        if (error == null || error.isBlank()) {
            return "unknown";
        }
        return error.length() <= MAX_ERROR_LENGTH ? error : error.substring(0, MAX_ERROR_LENGTH);
    }
}
