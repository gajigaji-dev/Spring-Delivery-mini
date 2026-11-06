package com.sparta.springdeliverymini.entity;

import com.sparta.springdeliverymini.exception.InvalidOrderStatusException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// Order의 상태 전이 규칙 검증 (DB·Spring 없이 순수 단위 테스트)
class OrderTest {

    private final Store store = new Store("치킨집", null);

    private Order orderWith(OrderStatus status) {
        // 상태 전이만 검증하므로 회원·점주는 필요 없음
        Order order = new Order(null, store, "서울시", status);
        order.addItem(new Menu("후라이드 치킨", 10000, "바삭한 치킨", store), 1);
        return order;
    }

    // ===== 주문 항목 =====

    @Test
    void 총액은_항목별_단가_곱하기_수량의_합이다() {
        Order order = new Order(null, store, "서울시", OrderStatus.ORDER_REQUEST);

        order.addItem(new Menu("후라이드 치킨", 18000, "바삭한 치킨", store), 2);
        order.addItem(new Menu("콜라", 2000, "1.25L", store), 3);

        assertThat(order.getTotalPrice()).isEqualTo(18000 * 2 + 2000 * 3);
        assertThat(order.getOrderItems()).extracting(OrderItem::getLinePrice).containsExactly(36000L, 6000L);
    }

    @Test
    void 항목은_주문_당시_메뉴_이름과_단가를_저장한다() {
        Menu menu = new Menu("후라이드 치킨", 18000, "바삭한 치킨", store);
        Order order = new Order(null, store, "서울시", OrderStatus.ORDER_REQUEST);
        order.addItem(menu, 1);

        menu.update("크리스피 치킨", 20000, "가격 인상");

        OrderItem item = order.getOrderItems().get(0);
        assertThat(item.getMenuName()).isEqualTo("후라이드 치킨");
        assertThat(item.getUnitPrice()).isEqualTo(18000);
        assertThat(order.getTotalPrice()).isEqualTo(18000);
    }

    @Test
    void 다른_매장의_메뉴는_담을_수_없다() {
        Order order = new Order(null, store, "서울시", OrderStatus.ORDER_REQUEST);
        Menu otherStoreMenu = new Menu("피자", 20000, "치즈 피자", new Store("피자집", null));

        assertThatThrownBy(() -> order.addItem(otherStoreMenu, 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(order.getOrderItems()).isEmpty();
    }

    @Test
    void 같은_메뉴를_두_번_담을_수_없다() {
        Order order = new Order(null, store, "서울시", OrderStatus.ORDER_REQUEST);
        Menu menu = new Menu("후라이드 치킨", 18000, "바삭한 치킨", store);
        order.addItem(menu, 1);

        assertThatThrownBy(() -> order.addItem(menu, 2))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(order.getOrderItems()).hasSize(1);
    }

    @Test
    void 총액이_long_범위를_넘으면_항목과_총액은_그대로다() {
        Order order = new Order(null, store, "서울시", OrderStatus.ORDER_REQUEST);
        order.addItem(new Menu("비싼 메뉴", Long.MAX_VALUE, "경계값", store), 1);

        assertThatThrownBy(() -> order.addItem(new Menu("콜라", 1, "1원", store), 1))
                .isInstanceOf(ArithmeticException.class);
        assertThat(order.getOrderItems()).hasSize(1);
        assertThat(order.getTotalPrice()).isEqualTo(Long.MAX_VALUE);
    }

    @Test
    void 항목_목록은_밖에서_바꿀_수_없다() {
        Order order = orderWith(OrderStatus.ORDER_REQUEST);

        assertThatThrownBy(() -> order.getOrderItems().clear())
                .isInstanceOf(UnsupportedOperationException.class);
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
