package com.sparta.springdeliverymini.entity;

import com.sparta.springdeliverymini.exception.InvalidSettlementStatusException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// 정산 집계·상태 전이(Settlement)와 합계(SettlementTotals) 규칙 검증 (DB 없이)
class SettlementTest {

    private static final LocalDate NOV_5 = LocalDate.of(2026, 11, 5);
    private static final BigDecimal RATE_5 = new BigDecimal("0.0500");
    private static final LocalDateTime AGGREGATED_AT = LocalDateTime.of(2026, 11, 6, 1, 10);
    private static final LocalDateTime CONFIRMED_AT = LocalDateTime.of(2026, 11, 6, 9, 0);
    private static final LocalDateTime PAID_AT = LocalDateTime.of(2026, 11, 7, 10, 0);

    private final Store store = new Store("치킨집", new User("owner1", "encoded-password", Role.OWNER));

    private static Payment payment(long amount, BigDecimal rate) {
        return new Payment(null, amount, rate, PaymentMethod.CARD, LocalDateTime.of(2026, 11, 5, 12, 0));
    }

    private Settlement pending() {
        return Settlement.aggregate(store, NOV_5, new SettlementTotals(1, 10000, 500, 9500), AGGREGATED_AT);
    }

    private Settlement confirmed() {
        Settlement settlement = pending();
        settlement.confirm(CONFIRMED_AT);
        return settlement;
    }

    private Settlement paid() {
        Settlement settlement = confirmed();
        settlement.markPaid(PAID_AT);
        return settlement;
    }

    // ---- SettlementTotals ----

    @Test
    void 합계는_결제마다_저장된_금액을_더하기만_한다() {
        // 18,009 × 5% = 900.45 → 900, 20,000 × 5% = 1,000, 수수료 0% 결제(V3 이전)도 그대로 합산
        SettlementTotals totals = SettlementTotals.of(List.of(
                payment(18009, RATE_5), payment(20000, RATE_5), payment(5000, BigDecimal.ZERO)));

        assertThat(totals.paymentCount()).isEqualTo(3);
        assertThat(totals.totalAmount()).isEqualTo(43009L);
        assertThat(totals.totalFee()).isEqualTo(1900L);
        assertThat(totals.settlementAmount()).isEqualTo(17109L + 19000L + 5000L);
        assertThat(totals.totalFee() + totals.settlementAmount()).isEqualTo(totals.totalAmount());
    }

    @Test
    void 결제가_없으면_합계는_0이다() {
        assertThat(SettlementTotals.of(List.of())).isEqualTo(new SettlementTotals(0, 0, 0, 0));
    }

    @Test
    void 합계가_long_범위를_넘으면_예외() {
        List<Payment> payments = List.of(payment(Long.MAX_VALUE, BigDecimal.ZERO), payment(1, BigDecimal.ZERO));

        assertThatThrownBy(() -> SettlementTotals.of(payments)).isInstanceOf(ArithmeticException.class);
    }

    // ---- 집계 (생성) ----

    @Test
    void 집계하면_합계가_고정되고_PENDING_상태로_생성된다() {
        Settlement settlement = Settlement.aggregate(store, NOV_5, new SettlementTotals(2, 38009, 1900, 36109), AGGREGATED_AT);

        assertThat(settlement.getStore()).isSameAs(store);
        assertThat(settlement.getSettlementDate()).isEqualTo(NOV_5);
        assertThat(settlement.getPaymentCount()).isEqualTo(2);
        assertThat(settlement.getTotalAmount()).isEqualTo(38009L);
        assertThat(settlement.getTotalFee()).isEqualTo(1900L);
        assertThat(settlement.getSettlementAmount()).isEqualTo(36109L);
        assertThat(settlement.getStatus()).isEqualTo(SettlementStatus.PENDING);
        assertThat(settlement.getConfirmedAt()).isNull();
        assertThat(settlement.getPaidAt()).isNull();
    }

    @Test
    void 다음날_01시_정각부터_집계할_수_있다() {
        Settlement settlement = Settlement.aggregate(store, NOV_5, new SettlementTotals(0, 0, 0, 0),
                LocalDateTime.of(2026, 11, 6, 1, 0));

        assertThat(settlement.getStatus()).isEqualTo(SettlementStatus.PENDING);
    }

    @Test
    void 집계_가능_시각_전에는_집계할_수_없다() {
        // 11/5는 11/6 01:00부터 집계 → 00:59:59에는 아직 그날 배달완료가 커밋될 수 있음
        assertThatThrownBy(() -> Settlement.aggregate(store, NOV_5, new SettlementTotals(0, 0, 0, 0),
                LocalDateTime.of(2026, 11, 6, 0, 59, 59)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("2026-11-04까지 집계 가능");
    }

    @Test
    void 필수_값이_없으면_집계할_수_없다() {
        SettlementTotals totals = new SettlementTotals(0, 0, 0, 0);

        assertThatThrownBy(() -> Settlement.aggregate(null, NOV_5, totals, AGGREGATED_AT)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Settlement.aggregate(store, null, totals, AGGREGATED_AT)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Settlement.aggregate(store, NOV_5, null, AGGREGATED_AT)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Settlement.aggregate(store, NOV_5, totals, null)).isInstanceOf(IllegalArgumentException.class);
    }

    // ---- PENDING → CONFIRMED ----

    @Test
    void 확정_대기_정산을_확정하면_CONFIRMED가_되고_확정_시각이_기록된다() {
        Settlement settlement = pending();

        settlement.confirm(CONFIRMED_AT);

        assertThat(settlement.getStatus()).isEqualTo(SettlementStatus.CONFIRMED);
        assertThat(settlement.getConfirmedAt()).isEqualTo(CONFIRMED_AT);
        assertThat(settlement.getPaidAt()).isNull();
    }

    @Test
    void 이미_확정된_정산은_다시_확정할_수_없다() {
        Settlement settlement = confirmed();

        assertThatThrownBy(() -> settlement.confirm(CONFIRMED_AT.plusHours(1)))
                .isInstanceOf(InvalidSettlementStatusException.class);
        assertThat(settlement.getConfirmedAt()).isEqualTo(CONFIRMED_AT); // 처음 확정 시각 유지
    }

    @Test
    void 지급된_정산은_확정으로_되돌릴_수_없다() {
        Settlement settlement = paid();

        assertThatThrownBy(() -> settlement.confirm(PAID_AT.plusHours(1)))
                .isInstanceOf(InvalidSettlementStatusException.class);
        assertThat(settlement.getStatus()).isEqualTo(SettlementStatus.PAID);
    }

    @Test
    void 확정_시각_없이_확정할_수_없다() {
        Settlement settlement = pending();

        assertThatThrownBy(() -> settlement.confirm(null)).isInstanceOf(IllegalArgumentException.class);
        assertThat(settlement.getStatus()).isEqualTo(SettlementStatus.PENDING);
    }

    // ---- CONFIRMED → PAID ----

    @Test
    void 확정된_정산을_지급하면_PAID가_되고_지급_시각이_기록된다() {
        Settlement settlement = confirmed();

        settlement.markPaid(PAID_AT);

        assertThat(settlement.getStatus()).isEqualTo(SettlementStatus.PAID);
        assertThat(settlement.getPaidAt()).isEqualTo(PAID_AT);
        assertThat(settlement.getConfirmedAt()).isEqualTo(CONFIRMED_AT);
    }

    @Test
    void 확정_전_정산은_지급할_수_없다() {
        Settlement settlement = pending();

        assertThatThrownBy(() -> settlement.markPaid(PAID_AT))
                .isInstanceOf(InvalidSettlementStatusException.class)
                .hasMessageContaining("PENDING");
        assertThat(settlement.getStatus()).isEqualTo(SettlementStatus.PENDING);
        assertThat(settlement.getPaidAt()).isNull();
    }

    @Test
    void 이미_지급된_정산은_다시_지급할_수_없다() {
        Settlement settlement = paid();

        assertThatThrownBy(() -> settlement.markPaid(PAID_AT.plusDays(1)))
                .isInstanceOf(InvalidSettlementStatusException.class);
        assertThat(settlement.getPaidAt()).isEqualTo(PAID_AT); // 처음 지급 시각 유지
    }

    @Test
    void 지급_시각_없이_지급할_수_없다() {
        Settlement settlement = confirmed();

        assertThatThrownBy(() -> settlement.markPaid(null)).isInstanceOf(IllegalArgumentException.class);
        assertThat(settlement.getStatus()).isEqualTo(SettlementStatus.CONFIRMED);
    }

    @Test
    void 지급_시각이_확정_시각보다_앞서면_지급할_수_없다() {
        Settlement settlement = confirmed();

        assertThatThrownBy(() -> settlement.markPaid(CONFIRMED_AT.minusSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(settlement.getStatus()).isEqualTo(SettlementStatus.CONFIRMED);
    }
}
