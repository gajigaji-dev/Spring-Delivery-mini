package com.sparta.springdeliverymini.entity;

import com.sparta.springdeliverymini.exception.InvalidPaymentStatusException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// Payment의 취소·환불 상태 전이 규칙 검증 (docs/settlement-policy.md 9장)
class PaymentTest {

    private static final LocalDateTime PAID_AT = LocalDateTime.of(2026, 11, 5, 11, 0);
    private static final LocalDateTime DELIVERED_AT = LocalDateTime.of(2026, 11, 5, 12, 0);
    private static final LocalDateTime REFUNDED_AT = LocalDateTime.of(2026, 11, 7, 10, 0);

    private static Order order(OrderStatus status) {
        Store store = new Store("치킨집", new User("owner1", "pw", Role.OWNER));
        Order order = new Order(new User("customer1", "pw", Role.CUSTOMER), store, "서울시", status);
        if (status == OrderStatus.DELIVERY_COMPLETED) {
            ReflectionTestUtils.setField(order, "deliveredAt", DELIVERED_AT);
        }
        return order;
    }

    private static Payment payment(OrderStatus orderStatus) {
        return new Payment(order(orderStatus), 18009, new BigDecimal("0.05"), PaymentMethod.CARD, PAID_AT);
    }

    @Test
    void 결제는_COMPLETED로_시작한다() {
        Payment payment = payment(OrderStatus.PAYMENT_COMPLETED);

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.COMPLETED);
        assertThat(payment.getCancelledAt()).isNull();
        assertThat(payment.getRefundedAt()).isNull();
    }

    // ===== 취소 =====

    @Test
    void 완료된_결제를_취소하면_CANCELLED와_취소_시각이_기록된다() {
        Payment payment = payment(OrderStatus.PAYMENT_COMPLETED);
        LocalDateTime cancelledAt = PAID_AT.plusMinutes(5);

        payment.cancel(cancelledAt);

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.CANCELLED);
        assertThat(payment.getCancelledAt()).isEqualTo(cancelledAt);
        assertThat(payment.getRefundedAt()).isNull();
        // 금액은 그대로 (전액 취소)
        assertThat(payment.getAmount()).isEqualTo(18009);
        assertThat(payment.getFeeAmount()).isEqualTo(900);
        assertThat(payment.getSettlementAmount()).isEqualTo(17109);
    }

    @Test
    void 이미_취소된_결제는_다시_취소할_수_없고_취소_시각도_유지된다() {
        Payment payment = payment(OrderStatus.PAYMENT_COMPLETED);
        payment.cancel(PAID_AT.plusMinutes(5));

        assertThatThrownBy(() -> payment.cancel(PAID_AT.plusMinutes(10)))
                .isInstanceOf(InvalidPaymentStatusException.class);
        assertThat(payment.getCancelledAt()).isEqualTo(PAID_AT.plusMinutes(5));
    }

    @Test
    void 환불된_결제는_취소할_수_없다() {
        Payment payment = payment(OrderStatus.DELIVERY_COMPLETED);
        payment.refund(REFUNDED_AT);

        assertThatThrownBy(() -> payment.cancel(REFUNDED_AT.plusMinutes(1)))
                .isInstanceOf(InvalidPaymentStatusException.class);
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.REFUNDED);
    }

    @Test
    void 취소_시각이_없으면_취소할_수_없다() {
        Payment payment = payment(OrderStatus.PAYMENT_COMPLETED);

        assertThatThrownBy(() -> payment.cancel(null)).isInstanceOf(IllegalArgumentException.class);
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.COMPLETED);
    }

    // ===== 환불 =====

    @Test
    void 배달완료_주문의_결제를_환불하면_REFUNDED와_환불_시각이_기록된다() {
        Payment payment = payment(OrderStatus.DELIVERY_COMPLETED);

        payment.refund(REFUNDED_AT);

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.REFUNDED);
        assertThat(payment.getRefundedAt()).isEqualTo(REFUNDED_AT);
        assertThat(payment.getCancelledAt()).isNull();
        // 결제액·수수료·정산액은 그대로 → 정산에서 이 금액 그대로 차감
        assertThat(payment.getAmount()).isEqualTo(18009);
        assertThat(payment.getFeeAmount()).isEqualTo(900);
        assertThat(payment.getSettlementAmount()).isEqualTo(17109);
    }

    @ParameterizedTest
    @EnumSource(value = OrderStatus.class, names = "DELIVERY_COMPLETED", mode = EnumSource.Mode.EXCLUDE)
    void 배달완료_전_주문의_결제는_환불할_수_없다(OrderStatus status) {
        Payment payment = payment(status);

        assertThatThrownBy(() -> payment.refund(REFUNDED_AT))
                .isInstanceOf(InvalidPaymentStatusException.class);
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.COMPLETED);
        assertThat(payment.getRefundedAt()).isNull();
    }

    @Test
    void 이미_환불된_결제는_다시_환불할_수_없고_환불_시각도_유지된다() {
        Payment payment = payment(OrderStatus.DELIVERY_COMPLETED);
        payment.refund(REFUNDED_AT);

        assertThatThrownBy(() -> payment.refund(REFUNDED_AT.plusDays(1)))
                .isInstanceOf(InvalidPaymentStatusException.class);
        assertThat(payment.getRefundedAt()).isEqualTo(REFUNDED_AT);
    }

    @Test
    void 취소된_결제는_환불할_수_없다() {
        Payment payment = payment(OrderStatus.DELIVERY_COMPLETED);
        payment.cancel(PAID_AT.plusMinutes(5));

        assertThatThrownBy(() -> payment.refund(REFUNDED_AT))
                .isInstanceOf(InvalidPaymentStatusException.class);
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.CANCELLED);
    }

    @Test
    void 환불_시각이_없거나_배달완료보다_앞서면_환불할_수_없다() {
        Payment payment = payment(OrderStatus.DELIVERY_COMPLETED);

        assertThatThrownBy(() -> payment.refund(null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> payment.refund(DELIVERED_AT.minusSeconds(1))).isInstanceOf(IllegalArgumentException.class);
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.COMPLETED);
    }
}
