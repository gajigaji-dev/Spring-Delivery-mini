package com.sparta.springdeliverymini.entity;

import com.sparta.springdeliverymini.exception.InvalidOrderStatusException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// Order의 상태 전이 규칙 검증 (DB·Spring 없이 순수 단위 테스트)
class OrderTest {

    private Order orderWith(OrderStatus status) {
        // 상태 전이만 검증하므로 메뉴·회원은 필요 없음
        return new Order(null, null, 1, 10000, "서울시", status);
    }

    // ===== 정상 흐름 =====

    @Test
    void 주문요청_결제_수락_배달완료_순서로_진행된다() {
        Order order = orderWith(OrderStatus.ORDER_REQUEST);

        order.pay();
        assertThat(order.getStatus()).isEqualTo(OrderStatus.PAYMENT_COMPLETED);

        order.accept();
        assertThat(order.getStatus()).isEqualTo(OrderStatus.ORDER_ACCEPTED);

        order.complete();
        assertThat(order.getStatus()).isEqualTo(OrderStatus.DELIVERY_COMPLETED);
    }

    @Test
    void 주문요청_상태에서_취소할_수_있다() {
        Order order = orderWith(OrderStatus.ORDER_REQUEST);

        order.cancel();

        assertThat(order.getStatus()).isEqualTo(OrderStatus.ORDER_CANCELLED);
    }

    // ===== 허용되지 않는 전이 =====

    @ParameterizedTest
    @EnumSource(value = OrderStatus.class, names = "ORDER_REQUEST", mode = EnumSource.Mode.EXCLUDE)
    void 주문요청이_아니면_취소할_수_없다(OrderStatus status) {
        Order order = orderWith(status);

        assertThatThrownBy(order::cancel)
                .isInstanceOf(InvalidOrderStatusException.class)
                .hasMessage("주문요청 상태에서만 주문을 취소할 수 있습니다.");
        assertThat(order.getStatus()).isEqualTo(status); // 상태는 그대로
    }

    @ParameterizedTest
    @EnumSource(value = OrderStatus.class, names = "ORDER_REQUEST", mode = EnumSource.Mode.EXCLUDE)
    void 주문요청이_아니면_결제할_수_없다(OrderStatus status) {
        Order order = orderWith(status);

        assertThatThrownBy(order::pay)
                .isInstanceOf(InvalidOrderStatusException.class)
                .hasMessage("주문요청 상태의 주문만 결제할 수 있습니다.");
        assertThat(order.getStatus()).isEqualTo(status);
    }

    @ParameterizedTest
    @EnumSource(value = OrderStatus.class, names = {"ORDER_REQUEST", "ORDER_CANCELLED", "ORDER_ACCEPTED"})
    void 결제완료가_아니면_수락할_수_없다(OrderStatus status) {
        Order order = orderWith(status);

        assertThatThrownBy(order::accept)
                .isInstanceOf(InvalidOrderStatusException.class)
                .hasMessage("허용되지 않는 주문 상태 변경입니다.");
        assertThat(order.getStatus()).isEqualTo(status);
    }

    @ParameterizedTest
    @EnumSource(value = OrderStatus.class, names = {"ORDER_REQUEST", "ORDER_CANCELLED", "PAYMENT_COMPLETED"})
    void 주문수락이_아니면_배달완료할_수_없다(OrderStatus status) {
        // 결제 전 주문의 배달완료, 단계 건너뛰기(결제완료 → 배달완료) 포함
        Order order = orderWith(status);

        assertThatThrownBy(order::complete)
                .isInstanceOf(InvalidOrderStatusException.class)
                .hasMessage("허용되지 않는 주문 상태 변경입니다.");
        assertThat(order.getStatus()).isEqualTo(status);
    }

    @Test
    void 배달완료된_주문은_수락_배달완료_모두_불가() {
        Order order = orderWith(OrderStatus.DELIVERY_COMPLETED);

        assertThatThrownBy(order::accept)
                .isInstanceOf(InvalidOrderStatusException.class)
                .hasMessage("이미 배달완료된 주문은 상태를 변경할 수 없습니다.");
        assertThatThrownBy(order::complete)
                .isInstanceOf(InvalidOrderStatusException.class)
                .hasMessage("이미 배달완료된 주문은 상태를 변경할 수 없습니다.");
    }
}
