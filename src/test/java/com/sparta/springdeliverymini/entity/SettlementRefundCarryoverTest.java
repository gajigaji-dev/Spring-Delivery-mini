package com.sparta.springdeliverymini.entity;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// 환불 차감·이월 계산(Settlement)과 차감 연결 기록(SettlementRefund, SettlementCarryover)의 규칙 검증
// docs/settlement-policy.md 9장
class SettlementRefundCarryoverTest {

    private static final LocalDate NOV_5 = LocalDate.of(2026, 11, 5);
    private static final LocalDate NOV_6 = LocalDate.of(2026, 11, 6);
    private static final LocalDateTime AGGREGATED_AT = LocalDateTime.of(2026, 11, 8, 1, 10);

    private final Store store = new Store("치킨집", new User("owner1", "pw", Role.OWNER));
    private final Store otherStore = new Store("분식집", new User("owner2", "pw", Role.OWNER));

    // 정산액 9500원짜리 판매 n건
    private static SettlementTotals sales(int count) {
        return new SettlementTotals(count, 10000L * count, 500L * count, 9500L * count);
    }

    private static SettlementTotals refunds(int count) {
        return sales(count);
    }

    private Settlement settlement(Store store, LocalDate date, SettlementTotals sales, SettlementTotals refunds, long carriedIn) {
        return Settlement.aggregate(store, date, sales, refunds, carriedIn, AGGREGATED_AT);
    }

    private Payment refundedPayment(Store store, LocalDateTime refundedAt) {
        Order order = new Order(new User("customer1", "pw", Role.CUSTOMER), store, "서울시", OrderStatus.DELIVERY_COMPLETED);
        ReflectionTestUtils.setField(order, "deliveredAt", LocalDateTime.of(2026, 11, 1, 12, 0));
        Payment payment = new Payment(order, 10000, new BigDecimal("0.05"), PaymentMethod.CARD, LocalDateTime.of(2026, 11, 1, 11, 0));
        payment.refund(refundedAt);
        return payment;
    }

    // ===== 지급액·이월 계산 =====

    @Test
    void 판매만_있으면_지급액은_판매_정산액이다() {
        Settlement s = settlement(store, NOV_5, sales(2), SettlementTotals.EMPTY, 0);

        assertThat(s.getSalesSettlementAmount()).isEqualTo(19000);
        assertThat(s.getSettlementAmount()).isEqualTo(19000);
        assertThat(s.getCarriedOutAmount()).isZero();
    }

    @Test
    void 판매와_같은_금액이_환불되면_지급액과_이월이_모두_0이다() {
        Settlement s = settlement(store, NOV_5, sales(1), refunds(1), 0);

        assertThat(s.getSettlementAmount()).isZero();
        assertThat(s.getCarriedOutAmount()).isZero();
    }

    @Test
    void 환불이_판매보다_크면_지급액은_0이고_차액을_이월한다() {
        Settlement s = settlement(store, NOV_5, sales(1), refunds(3), 0);

        assertThat(s.getRefundCount()).isEqualTo(3);
        assertThat(s.getRefundAmount()).isEqualTo(30000);
        assertThat(s.getRefundFee()).isEqualTo(1500);          // 플랫폼 수수료도 취소
        assertThat(s.getRefundSettlementAmount()).isEqualTo(28500);
        assertThat(s.getSettlementAmount()).isZero();
        assertThat(s.getCarriedOutAmount()).isEqualTo(19000);  // 28500 - 9500
    }

    @Test
    void 이월받은_금액을_판매에서_모두_차감할_수_있으면_남은_금액을_지급한다() {
        Settlement s = settlement(store, NOV_5, sales(2), SettlementTotals.EMPTY, 5000);

        assertThat(s.getCarriedInAmount()).isEqualTo(5000);
        assertThat(s.getSettlementAmount()).isEqualTo(14000);
        assertThat(s.getCarriedOutAmount()).isZero();
    }

    @Test
    void 이월받은_금액이_판매보다_크면_다시_이월한다() {
        Settlement s = settlement(store, NOV_5, sales(1), refunds(1), 3000);

        assertThat(s.getSettlementAmount()).isZero();
        assertThat(s.getCarriedOutAmount()).isEqualTo(3000);
    }

    @Test
    void 정확히_0이_되는_경계에서는_지급액과_이월이_모두_0이다() {
        Settlement s = settlement(store, NOV_5, sales(1), SettlementTotals.EMPTY, 9500);

        assertThat(s.getSettlementAmount()).isZero();
        assertThat(s.getCarriedOutAmount()).isZero();
    }

    @Test
    void 판매_없이_환불만_있으면_환불_정산액_전부를_이월한다() {
        Settlement s = settlement(store, NOV_5, SettlementTotals.EMPTY, refunds(1), 0);

        assertThat(s.getPaymentCount()).isZero();
        assertThat(s.getSettlementAmount()).isZero();
        assertThat(s.getCarriedOutAmount()).isEqualTo(9500);
    }

    @Test
    void 금액_계산이_long_범위를_넘으면_예외() {
        SettlementTotals huge = new SettlementTotals(1, Long.MAX_VALUE, 0, Long.MAX_VALUE);

        assertThatThrownBy(() -> settlement(store, NOV_5, SettlementTotals.EMPTY, huge, 1))
                .isInstanceOf(ArithmeticException.class);
    }

    @Test
    void 이월받은_금액이_음수면_예외() {
        assertThatThrownBy(() -> settlement(store, NOV_5, sales(1), SettlementTotals.EMPTY, -1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ===== 환불 차감 기록 =====

    @Test
    void 환불_차감_기록은_결제_금액을_그대로_스냅샷한다() {
        Settlement s = settlement(store, NOV_5, SettlementTotals.EMPTY, refunds(1), 0);
        Payment refund = refundedPayment(store, LocalDateTime.of(2026, 11, 5, 23, 59, 59));

        SettlementRefund link = new SettlementRefund(s, refund);

        assertThat(link.getAmount()).isEqualTo(10000);
        assertThat(link.getFeeAmount()).isEqualTo(500);
        assertThat(link.getSettlementAmount()).isEqualTo(9500);
    }

    @Test
    void 환불보다_이른_날짜의_정산에서는_차감할_수_없다() {
        Settlement s = settlement(store, NOV_5, sales(1), SettlementTotals.EMPTY, 0);
        // 11/6 00:00 환불 → 11/5 정산 구간의 끝(미포함)과 같으므로 11/5에서는 차감 불가
        Payment refund = refundedPayment(store, LocalDateTime.of(2026, 11, 6, 0, 0));

        assertThatThrownBy(() -> new SettlementRefund(s, refund)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 환불되지_않은_결제나_다른_매장_결제는_차감할_수_없다() {
        Settlement s = settlement(store, NOV_5, sales(1), SettlementTotals.EMPTY, 0);
        Payment completed = new Payment(new Order(new User("c", "pw", Role.CUSTOMER), store, "서울시",
                OrderStatus.DELIVERY_COMPLETED), 10000, new BigDecimal("0.05"), PaymentMethod.CARD, LocalDateTime.of(2026, 11, 1, 11, 0));
        Payment otherStoreRefund = refundedPayment(otherStore, LocalDateTime.of(2026, 11, 5, 9, 0));

        assertThatThrownBy(() -> new SettlementRefund(s, completed)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SettlementRefund(s, otherStoreRefund)).isInstanceOf(IllegalArgumentException.class);
    }

    // ===== 이월 차감 기록 =====

    @Test
    void 이월_기록은_넘긴_정산의_이월액을_그대로_옮긴다() {
        Settlement from = settlement(store, NOV_5, SettlementTotals.EMPTY, refunds(1), 0);
        Settlement to = settlement(store, NOV_6, sales(2), SettlementTotals.EMPTY, from.getCarriedOutAmount());

        SettlementCarryover link = new SettlementCarryover(from, to);

        assertThat(link.getAmount()).isEqualTo(9500);
        assertThat(to.getSettlementAmount()).isEqualTo(19000 - 9500);
    }

    @Test
    void 이월분은_같거나_앞선_날짜의_정산으로_넘길_수_없다() {
        Settlement nov6 = settlement(store, NOV_6, SettlementTotals.EMPTY, refunds(1), 0);
        Settlement nov5 = settlement(store, NOV_5, sales(2), SettlementTotals.EMPTY, 0);
        Settlement otherNov6 = settlement(store, NOV_6, sales(1), SettlementTotals.EMPTY, 0);

        assertThatThrownBy(() -> new SettlementCarryover(nov6, nov5)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SettlementCarryover(nov6, otherNov6)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SettlementCarryover(nov6, nov6)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 이월액이_없거나_다른_매장_정산이면_기록할_수_없다() {
        Settlement noCarry = settlement(store, NOV_5, sales(1), SettlementTotals.EMPTY, 0);
        Settlement carry = settlement(store, NOV_5, SettlementTotals.EMPTY, refunds(1), 0);
        Settlement next = settlement(store, NOV_6, sales(1), SettlementTotals.EMPTY, 0);
        Settlement otherStoreNext = settlement(otherStore, NOV_6, sales(1), SettlementTotals.EMPTY, 0);

        assertThatThrownBy(() -> new SettlementCarryover(noCarry, next)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SettlementCarryover(carry, otherStoreNext)).isInstanceOf(IllegalArgumentException.class);
    }
}
