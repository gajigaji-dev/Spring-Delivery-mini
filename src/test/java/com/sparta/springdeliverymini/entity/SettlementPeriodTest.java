package com.sparta.springdeliverymini.entity;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// 정산 기간 규칙 검증 (docs/settlement-policy.md 3·4장)
class SettlementPeriodTest {

    private static final LocalDate NOV_1 = LocalDate.of(2026, 11, 1);
    private static final LocalDate NOV_5 = LocalDate.of(2026, 11, 5);
    // 11/6 01:00 이후 → 11/5까지 확정
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 6, 9, 0);

    // ===== 반열림 구간 =====

    @Test
    void 날짜_범위는_시작일_0시_포함_종료일_다음날_0시_미포함_구간으로_변환된다() {
        SettlementPeriod period = SettlementPeriod.of(NOV_1, NOV_5, NOW);

        assertThat(period.start()).isEqualTo(LocalDateTime.of(2026, 11, 1, 0, 0));
        assertThat(period.end()).isEqualTo(LocalDateTime.of(2026, 11, 6, 0, 0));
    }

    @Test
    void 하루만_조회하면_그날_0시부터_다음날_0시까지다() {
        SettlementPeriod period = SettlementPeriod.of(NOV_5, NOV_5, NOW);

        assertThat(period.start()).isEqualTo(LocalDateTime.of(2026, 11, 5, 0, 0));
        assertThat(period.end()).isEqualTo(LocalDateTime.of(2026, 11, 6, 0, 0));
    }

    // ===== 확정 시각 (D+1 01:00) =====

    @Test
    void 다음날_01시부터_전날이_확정된다() {
        assertThat(SettlementPeriod.lastConfirmedDate(LocalDateTime.of(2026, 11, 6, 1, 0)))
                .isEqualTo(NOV_5);
    }

    @Test
    void 다음날_01시_전에는_전날이_아직_확정되지_않는다() {
        assertThat(SettlementPeriod.lastConfirmedDate(LocalDateTime.of(2026, 11, 6, 0, 59, 59)))
                .isEqualTo(LocalDate.of(2026, 11, 4));
    }

    @Test
    void 확정되지_않은_날짜가_포함되면_거부한다() {
        LocalDateTime beforeConfirm = LocalDateTime.of(2026, 11, 6, 0, 30);

        assertThatThrownBy(() -> SettlementPeriod.of(NOV_1, NOV_5, beforeConfirm))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("2026-11-04까지");
    }

    @Test
    void 오늘은_조회할_수_없다() {
        assertThatThrownBy(() -> SettlementPeriod.of(NOV_1, NOW.toLocalDate(), NOW))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ===== 기간 검증 =====

    @Test
    void 시작일이_종료일보다_늦으면_거부한다() {
        assertThatThrownBy(() -> SettlementPeriod.of(NOV_5, NOV_1, NOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("시작일은 종료일보다 늦을 수 없습니다.");
    }

    @Test
    void 최대_31일까지_조회할_수_있다() {
        LocalDate to = LocalDate.of(2026, 10, 31);

        assertThat(SettlementPeriod.of(LocalDate.of(2026, 10, 1), to, NOW).to()).isEqualTo(to);
        assertThatThrownBy(() -> SettlementPeriod.of(LocalDate.of(2026, 9, 30), to, NOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("정산 조회 기간은 최대 31일입니다.");
    }
}
