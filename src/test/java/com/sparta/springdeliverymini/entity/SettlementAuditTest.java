package com.sparta.springdeliverymini.entity;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// 정산 상태 변경 이력(SettlementStatusHistory)과 집계 실패 기록(SettlementAggregationFailure)의 규칙 검증
// docs/settlement-policy.md 10장
class SettlementAuditTest {

    private static final LocalDate NOV_5 = LocalDate.of(2026, 11, 5);
    private static final LocalDateTime AGGREGATED_AT = LocalDateTime.of(2026, 11, 6, 1, 10);
    private static final LocalDateTime CONFIRMED_AT = LocalDateTime.of(2026, 11, 6, 9, 0);
    private static final LocalDateTime PAID_AT = LocalDateTime.of(2026, 11, 7, 10, 0);

    private final Store store = new Store("치킨집", new User("owner1", "pw", Role.OWNER));
    private final User admin = new User("admin", "pw", Role.ADMIN);

    private Settlement pending() {
        return Settlement.aggregate(store, NOV_5, new SettlementTotals(1, 10000, 500, 9500), AGGREGATED_AT);
    }

    // ===== 상태 변경 이력 =====

    @Test
    void 집계_이력은_이전_상태가_없고_처리자가_없어도_된다() {
        SettlementStatusHistory history = SettlementStatusHistory.aggregated(pending(), null, AGGREGATED_AT);

        assertThat(history.getFromStatus()).isNull();
        assertThat(history.getToStatus()).isEqualTo(SettlementStatus.PENDING);
        assertThat(history.getActor()).isNull();
        assertThat(history.getChangedAt()).isEqualTo(AGGREGATED_AT);
    }

    @Test
    void 확정_지급_이력의_시각은_정산의_확정_지급_시각과_같다() {
        Settlement settlement = pending();
        settlement.confirm(CONFIRMED_AT);
        SettlementStatusHistory confirmed = SettlementStatusHistory.confirmed(settlement, admin);
        settlement.markPaid(PAID_AT);
        SettlementStatusHistory paid = SettlementStatusHistory.paid(settlement, admin);

        assertThat(confirmed.getFromStatus()).isEqualTo(SettlementStatus.PENDING);
        assertThat(confirmed.getToStatus()).isEqualTo(SettlementStatus.CONFIRMED);
        assertThat(confirmed.getChangedAt()).isEqualTo(CONFIRMED_AT);
        assertThat(paid.getFromStatus()).isEqualTo(SettlementStatus.CONFIRMED);
        assertThat(paid.getToStatus()).isEqualTo(SettlementStatus.PAID);
        assertThat(paid.getChangedAt()).isEqualTo(PAID_AT);
        assertThat(paid.getActor()).isSameAs(admin);
    }

    @Test
    void 확정_지급_이력은_처리자가_없으면_만들_수_없다() {
        Settlement settlement = pending();
        settlement.confirm(CONFIRMED_AT);

        assertThatThrownBy(() -> SettlementStatusHistory.confirmed(settlement, null))
                .isInstanceOf(IllegalArgumentException.class);
        settlement.markPaid(PAID_AT);
        assertThatThrownBy(() -> SettlementStatusHistory.paid(settlement, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 정산의_현재_상태와_다른_이력은_만들_수_없다() {
        // 아직 PENDING인 정산에 확정·지급 이력 → 상태 전이 없이 이력만 남는 것을 막음
        assertThatThrownBy(() -> SettlementStatusHistory.confirmed(pending(), admin))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SettlementStatusHistory.paid(pending(), admin))
                .isInstanceOf(IllegalArgumentException.class);

        Settlement confirmed = pending();
        confirmed.confirm(CONFIRMED_AT);
        assertThatThrownBy(() -> SettlementStatusHistory.aggregated(confirmed, null, AGGREGATED_AT))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ===== 집계 실패 기록 =====

    @Test
    void 실패_기록은_FAILED_1회로_시작한다() {
        SettlementAggregationFailure failure = new SettlementAggregationFailure(store, NOV_5, "오류", null, AGGREGATED_AT);

        assertThat(failure.isFailed()).isTrue();
        assertThat(failure.getFailureCount()).isEqualTo(1);
        assertThat(failure.getFirstFailedAt()).isEqualTo(AGGREGATED_AT);
        assertThat(failure.getLastFailedAt()).isEqualTo(AGGREGATED_AT);
        assertThat(failure.getResolvedAt()).isNull();
    }

    @Test
    void 오류_요약은_500자로_자르고_비어_있으면_unknown으로_저장한다() {
        SettlementAggregationFailure longError =
                new SettlementAggregationFailure(store, NOV_5, "가".repeat(600), null, AGGREGATED_AT);
        SettlementAggregationFailure noError = new SettlementAggregationFailure(store, NOV_5, null, null, AGGREGATED_AT);

        assertThat(longError.getLastError()).hasSize(500);
        assertThat(noError.getLastError()).isEqualTo("unknown");
    }

    @Test
    void 해결하면_해결_시각_처리자_정산이_기록되고_다시_해결할_수_없다() {
        SettlementAggregationFailure failure = new SettlementAggregationFailure(store, NOV_5, "오류", null, AGGREGATED_AT);
        Settlement settlement = pending();

        failure.resolve(settlement, admin, CONFIRMED_AT);

        assertThat(failure.getStatus()).isEqualTo(SettlementAggregationFailureStatus.RESOLVED);
        assertThat(failure.getResolvedAt()).isEqualTo(CONFIRMED_AT);
        assertThat(failure.getResolvedBy()).isSameAs(admin);
        assertThat(failure.getSettlement()).isSameAs(settlement);
        assertThatThrownBy(() -> failure.resolve(settlement, admin, PAID_AT))
                .isInstanceOf(IllegalStateException.class);
        assertThat(failure.getResolvedAt()).isEqualTo(CONFIRMED_AT);
    }

    @Test
    void 해결된_기록이_다시_실패하면_FAILED로_돌아가고_해결_정보가_지워진다() {
        SettlementAggregationFailure failure = new SettlementAggregationFailure(store, NOV_5, "오류", null, AGGREGATED_AT);
        failure.resolve(null, null, CONFIRMED_AT); // 집계할 대상이 없어 해결

        failure.recordFailure("새 오류", admin, PAID_AT);

        assertThat(failure.isFailed()).isTrue();
        assertThat(failure.getFailureCount()).isEqualTo(2);
        assertThat(failure.getLastError()).isEqualTo("새 오류");
        assertThat(failure.getFirstFailedAt()).isEqualTo(AGGREGATED_AT);
        assertThat(failure.getLastFailedAt()).isEqualTo(PAID_AT);
        assertThat(failure.getLastFailedBy()).isSameAs(admin);
        assertThat(failure.getResolvedAt()).isNull();
        assertThat(failure.getResolvedBy()).isNull();
        assertThat(failure.getSettlement()).isNull();
    }
}
