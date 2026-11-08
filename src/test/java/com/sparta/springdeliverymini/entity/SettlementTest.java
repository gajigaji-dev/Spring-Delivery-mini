package com.sparta.springdeliverymini.entity;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// 정산 확정(Settlement)·합계(SettlementTotals) 규칙 검증 (DB 없이)
class SettlementTest {

    private static final LocalDate NOV_5 = LocalDate.of(2026, 11, 5);
    private static final BigDecimal RATE_5 = new BigDecimal("0.0500");

    private final Store store = new Store("치킨집", new User("owner1", "encoded-password", Role.OWNER));

    private static Payment payment(long amount, BigDecimal rate) {
        return new Payment(null, amount, rate, PaymentMethod.CARD, LocalDateTime.of(2026, 11, 5, 12, 0));
    }

    private static Settlement confirmedSettlement() {
        return Settlement.confirm(new Store("치킨집", new User("owner1", "pw", Role.OWNER)), NOV_5,
                new SettlementTotals(1, 10000, 500, 9500), LocalDateTime.of(2026, 11, 6, 1, 10));
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

    // ---- 확정 ----

    @Test
    void 확정하면_합계가_고정되고_CONFIRMED_상태가_된다() {
        LocalDateTime confirmedAt = LocalDateTime.of(2026, 11, 6, 1, 10);

        Settlement settlement = Settlement.confirm(store, NOV_5, new SettlementTotals(2, 38009, 1900, 36109), confirmedAt);

        assertThat(settlement.getStore()).isSameAs(store);
        assertThat(settlement.getSettlementDate()).isEqualTo(NOV_5);
        assertThat(settlement.getPaymentCount()).isEqualTo(2);
        assertThat(settlement.getTotalAmount()).isEqualTo(38009L);
        assertThat(settlement.getTotalFee()).isEqualTo(1900L);
        assertThat(settlement.getSettlementAmount()).isEqualTo(36109L);
        assertThat(settlement.getStatus()).isEqualTo(SettlementStatus.CONFIRMED);
        assertThat(settlement.getConfirmedAt()).isEqualTo(confirmedAt);
        assertThat(settlement.getPaidAt()).isNull();
    }

    @Test
    void 다음날_01시_정각부터_확정할_수_있다() {
        Settlement settlement = Settlement.confirm(store, NOV_5, new SettlementTotals(0, 0, 0, 0),
                LocalDateTime.of(2026, 11, 6, 1, 0));

        assertThat(settlement.getStatus()).isEqualTo(SettlementStatus.CONFIRMED);
    }

    @Test
    void 확정_시각_전에는_확정할_수_없다() {
        // 11/5는 11/6 01:00에 확정 → 00:59:59에는 아직 그날 배달완료가 커밋될 수 있음
        assertThatThrownBy(() -> Settlement.confirm(store, NOV_5, new SettlementTotals(0, 0, 0, 0),
                LocalDateTime.of(2026, 11, 6, 0, 59, 59)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("2026-11-04까지 확정 가능");
    }

    @Test
    void 당일이나_미래_날짜는_확정할_수_없다() {
        assertThatThrownBy(() -> Settlement.confirm(store, NOV_5, new SettlementTotals(0, 0, 0, 0),
                LocalDateTime.of(2026, 11, 5, 23, 0)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 필수_값이_없으면_확정할_수_없다() {
        SettlementTotals totals = new SettlementTotals(0, 0, 0, 0);
        LocalDateTime now = LocalDateTime.of(2026, 11, 6, 9, 0);

        assertThatThrownBy(() -> Settlement.confirm(null, NOV_5, totals, now)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Settlement.confirm(store, null, totals, now)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Settlement.confirm(store, NOV_5, null, now)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Settlement.confirm(store, NOV_5, totals, null)).isInstanceOf(IllegalArgumentException.class);
    }

    // ---- 상태 변경 ----

    @Test
    void 확정된_정산은_지급완료로_바꿀_수_있다() {
        Settlement settlement = confirmedSettlement();
        LocalDateTime paidAt = LocalDateTime.of(2026, 11, 7, 10, 0);

        settlement.markPaid(paidAt);

        assertThat(settlement.getStatus()).isEqualTo(SettlementStatus.PAID);
        assertThat(settlement.getPaidAt()).isEqualTo(paidAt);
    }

    @Test
    void 이미_지급된_정산은_다시_지급할_수_없다() {
        Settlement settlement = confirmedSettlement();
        LocalDateTime firstPaidAt = LocalDateTime.of(2026, 11, 7, 10, 0);
        settlement.markPaid(firstPaidAt);

        assertThatThrownBy(() -> settlement.markPaid(LocalDateTime.of(2026, 11, 8, 10, 0)))
                .isInstanceOf(IllegalStateException.class);
        assertThat(settlement.getPaidAt()).isEqualTo(firstPaidAt); // 처음 지급 시각 유지
    }

    @Test
    void 지급_시각_없이_지급할_수_없다() {
        Settlement settlement = confirmedSettlement();

        assertThatThrownBy(() -> settlement.markPaid(null)).isInstanceOf(IllegalArgumentException.class);
        assertThat(settlement.getStatus()).isEqualTo(SettlementStatus.CONFIRMED);
    }
}
