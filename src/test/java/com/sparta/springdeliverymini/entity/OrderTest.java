package com.sparta.springdeliverymini.entity;


import com.sparta.springdeliverymini.TestAddresses;
import com.sparta.springdeliverymini.exception.ErrorCode;
import com.sparta.springdeliverymini.exception.InvalidDeliveryRequestException;
import com.sparta.springdeliverymini.exception.InvalidOrderStatusException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// Order의 상태 전이 규칙 검증 (DB·Spring 없이 순수 단위 테스트)
class OrderTest {

    private final Store store = new Store("치킨집", null);
    private static final LocalDateTime DELIVERED_AT = LocalDateTime.of(2026, 11, 5, 23, 59, 59);

    private Order orderWith(OrderStatus status) {
        // 상태 전이만 검증하므로 회원·점주는 필요 없음
        Order order = new Order(null, store, TestAddresses.seoul(), status);
        order.addItem(new Menu("후라이드 치킨", 10000, "바삭한 치킨", store), 1);
        return order;
    }

    // ===== 주문 항목 =====

    @Test
    void 총액은_항목별_단가_곱하기_수량의_합이다() {
        Order order = new Order(null, store, TestAddresses.seoul(), OrderStatus.ORDER_REQUEST);

        order.addItem(new Menu("후라이드 치킨", 18000, "바삭한 치킨", store), 2);
        order.addItem(new Menu("콜라", 2000, "1.25L", store), 3);

        assertThat(order.getTotalPrice()).isEqualTo(18000 * 2 + 2000 * 3);
        assertThat(order.getOrderItems()).extracting(OrderItem::getLinePrice).containsExactly(36000L, 6000L);
    }

    @Test
    void 항목은_주문_당시_메뉴_이름과_단가를_저장한다() {
        Menu menu = new Menu("후라이드 치킨", 18000, "바삭한 치킨", store);
        Order order = new Order(null, store, TestAddresses.seoul(), OrderStatus.ORDER_REQUEST);
        order.addItem(menu, 1);

        menu.update("크리스피 치킨", 20000, "가격 인상");

        OrderItem item = order.getOrderItems().get(0);
        assertThat(item.getMenuName()).isEqualTo("후라이드 치킨");
        assertThat(item.getUnitPrice()).isEqualTo(18000);
        assertThat(order.getTotalPrice()).isEqualTo(18000);
    }

    @Test
    void 다른_매장의_메뉴는_담을_수_없다() {
        Order order = new Order(null, store, TestAddresses.seoul(), OrderStatus.ORDER_REQUEST);
        Menu otherStoreMenu = new Menu("피자", 20000, "치즈 피자", new Store("피자집", null));

        assertThatThrownBy(() -> order.addItem(otherStoreMenu, 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(order.getOrderItems()).isEmpty();
    }

    @Test
    void 같은_메뉴를_두_번_담을_수_없다() {
        Order order = new Order(null, store, TestAddresses.seoul(), OrderStatus.ORDER_REQUEST);
        Menu menu = new Menu("후라이드 치킨", 18000, "바삭한 치킨", store);
        order.addItem(menu, 1);

        assertThatThrownBy(() -> order.addItem(menu, 2))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(order.getOrderItems()).hasSize(1);
    }

    @Test
    void 총액이_long_범위를_넘으면_항목과_총액은_그대로다() {
        Order order = new Order(null, store, TestAddresses.seoul(), OrderStatus.ORDER_REQUEST);
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

        order.complete(DELIVERED_AT);
        assertThat(order.getStatus()).isEqualTo(OrderStatus.DELIVERY_COMPLETED);
    }

    @Test
    void 배달완료하면_배달완료_시각이_기록된다() {
        Order order = orderWith(OrderStatus.ORDER_ACCEPTED);
        assertThat(order.getDeliveredAt()).isNull();

        order.complete(DELIVERED_AT);

        assertThat(order.getDeliveredAt()).isEqualTo(DELIVERED_AT);
    }

    @Test
    void 배달완료_시각_없이는_배달완료할_수_없다() {
        Order order = orderWith(OrderStatus.ORDER_ACCEPTED);

        assertThatThrownBy(() -> order.complete(null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(order.getStatus()).isEqualTo(OrderStatus.ORDER_ACCEPTED);
        assertThat(order.getDeliveredAt()).isNull();
    }

    // 주문수락 전(주문요청·결제완료)이면 취소 가능 — 결제완료 주문의 결제 취소는 OrderService가 함께 처리
    @ParameterizedTest
    @EnumSource(value = OrderStatus.class, names = {"ORDER_REQUEST", "PAYMENT_COMPLETED"})
    void 주문수락_전에는_취소할_수_있다(OrderStatus status) {
        Order order = orderWith(status);

        order.cancel();

        assertThat(order.getStatus()).isEqualTo(OrderStatus.ORDER_CANCELLED);
    }

    // ===== 허용되지 않는 전이 =====

    // 주문수락 이후(ORDER_ACCEPTED, DELIVERY_COMPLETED)는 취소 불가
    @ParameterizedTest
    @EnumSource(value = OrderStatus.class, names = {"ORDER_ACCEPTED", "DELIVERY_COMPLETED"})
    void 주문수락_이후에는_취소할_수_없다(OrderStatus status) {
        Order order = orderWith(status);

        assertThatThrownBy(order::cancel)
                .isInstanceOf(InvalidOrderStatusException.class)
                .hasMessage("주문수락 전(주문요청·결제완료) 상태에서만 주문을 취소할 수 있습니다.")
                .extracting("code").isEqualTo(ErrorCode.ORDER_NOT_CANCELLABLE);
        assertThat(order.getStatus()).isEqualTo(status); // 상태는 그대로
    }

    // 중복 취소 요청은 "취소 불가"가 아니라 "이미 취소됨"으로 구분 (#77)
    @Test
    void 이미_취소된_주문을_다시_취소하면_ORDER_ALREADY_CANCELLED() {
        Order order = orderWith(OrderStatus.ORDER_CANCELLED);

        assertThatThrownBy(order::cancel)
                .isInstanceOf(InvalidOrderStatusException.class)
                .extracting("code").isEqualTo(ErrorCode.ORDER_ALREADY_CANCELLED);
    }

    // 결제완료 이후 주문에 다시 결제 = 중복 결제 요청 → ORDER_ALREADY_PAID
    @ParameterizedTest
    @EnumSource(value = OrderStatus.class, names = {"PAYMENT_COMPLETED", "ORDER_ACCEPTED", "DELIVERY_COMPLETED"})
    void 이미_결제된_주문은_결제할_수_없다(OrderStatus status) {
        Order order = orderWith(status);

        assertThatThrownBy(order::pay)
                .isInstanceOf(InvalidOrderStatusException.class)
                .hasMessage("이미 결제된 주문입니다.")
                .extracting("code").isEqualTo(ErrorCode.ORDER_ALREADY_PAID);
        assertThat(order.getStatus()).isEqualTo(status);
    }

    @Test
    void 취소된_주문은_결제할_수_없다() {
        Order order = orderWith(OrderStatus.ORDER_CANCELLED);

        assertThatThrownBy(order::pay)
                .isInstanceOf(InvalidOrderStatusException.class)
                .hasMessage("취소된 주문은 결제할 수 없습니다.")
                .extracting("code").isEqualTo(ErrorCode.ORDER_ALREADY_CANCELLED);
    }

    // ===== 배달 요청사항 (V19) =====

    @Test
    void 배달_요청사항은_앞뒤_공백을_지워_저장하고_비었으면_null이다() {
        DeliveryAddress address = TestAddresses.seoul();

        assertThat(new Order(null, null, address, "  문 앞에 두고 벨 눌러 주세요 ", OrderStatus.ORDER_REQUEST).getDeliveryRequest())
                .isEqualTo("문 앞에 두고 벨 눌러 주세요");
        assertThat(new Order(null, null, address, "   ", OrderStatus.ORDER_REQUEST).getDeliveryRequest()).isNull();
        assertThat(new Order(null, null, address, OrderStatus.ORDER_REQUEST).getDeliveryRequest()).isNull();
    }

    @Test
    void 배달_요청사항은_100자까지_허용하고_넘으면_거부한다() {
        DeliveryAddress address = TestAddresses.seoul();

        assertThat(new Order(null, null, address, "가".repeat(100), OrderStatus.ORDER_REQUEST).getDeliveryRequest()).hasSize(100);
        assertThatThrownBy(() -> new Order(null, null, address, "가".repeat(101), OrderStatus.ORDER_REQUEST))
                .isInstanceOf(InvalidDeliveryRequestException.class)
                .hasMessage("배달 요청사항은 100자 이하여야 합니다.");
    }

    @Test
    void 배달_요청사항에_줄바꿈이_있으면_거부한다() {
        assertThatThrownBy(() -> new Order(null, null, TestAddresses.seoul(), "문 앞에\n두세요", OrderStatus.ORDER_REQUEST))
                .isInstanceOf(InvalidDeliveryRequestException.class);
    }

    @Test
    void 배송_주소_한_줄은_도로명과_상세_주소를_합친_값이다() {
        Order order = new Order(null, null, new DeliveryAddress("06236", "서울 강남구 테헤란로 152", "101호"),
                OrderStatus.ORDER_REQUEST);

        assertThat(order.getDeliveryAddress()).isEqualTo("서울 강남구 테헤란로 152 101호");
        assertThat(order.getAddress().getZipCode()).isEqualTo("06236");
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

        assertThatThrownBy(() -> order.complete(DELIVERED_AT))
                .isInstanceOf(InvalidOrderStatusException.class)
                .hasMessage("허용되지 않는 주문 상태 변경입니다.");
        assertThat(order.getStatus()).isEqualTo(status);
        assertThat(order.getDeliveredAt()).isNull(); // 실패하면 시각도 기록되지 않음
    }

    @Test
    void 배달완료된_주문은_수락_배달완료_모두_불가() {
        Order order = orderWith(OrderStatus.DELIVERY_COMPLETED);

        assertThatThrownBy(order::accept)
                .isInstanceOf(InvalidOrderStatusException.class)
                .hasMessage("이미 배달완료된 주문은 상태를 변경할 수 없습니다.");
        assertThatThrownBy(() -> order.complete(DELIVERED_AT))
                .isInstanceOf(InvalidOrderStatusException.class)
                .hasMessage("이미 배달완료된 주문은 상태를 변경할 수 없습니다.");
    }
}
