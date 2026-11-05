package com.sparta.springdeliverymini.service;

import com.sparta.springdeliverymini.dto.OrderCreateRequest;
import com.sparta.springdeliverymini.dto.OrderResponse;
import com.sparta.springdeliverymini.entity.*;
import com.sparta.springdeliverymini.exception.ApiException;
import com.sparta.springdeliverymini.exception.InvalidOrderStatusException;
import com.sparta.springdeliverymini.repository.MenuRepository;
import com.sparta.springdeliverymini.repository.OrderRepository;
import com.sparta.springdeliverymini.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

// OrderService가 권한·본인 확인 후 Order의 상태 전이 메서드를 올바르게 호출하는지 검증
// Repository는 Mock으로 대체 → DB 없이 실행
@ExtendWith(MockitoExtension.class)
class OrderServiceTest {

    @Mock
    private OrderRepository orderRepository;

    @Mock
    private MenuRepository menuRepository;

    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private OrderService orderService;

    private final User customer = user(1L, "customer1", Role.CUSTOMER);
    private final User otherCustomer = user(2L, "customer2", Role.CUSTOMER);
    private final User owner = user(3L, "owner1", Role.OWNER);
    private final User otherOwner = user(4L, "owner2", Role.OWNER);

    // id는 DB가 생성하므로 테스트에서는 리플렉션으로 직접 지정
    private static User user(Long id, String username, Role role) {
        User user = new User(username, "encoded-password", role);
        ReflectionTestUtils.setField(user, "id", id);
        return user;
    }

    // customer가 owner의 메뉴를 주문한 상태
    private Order order(OrderStatus status) {
        Menu menu = new Menu("후라이드 치킨", 18000, "바삭한 치킨", owner);
        Order order = new Order(menu, customer, 1, 18000, "서울시", status);
        ReflectionTestUtils.setField(order, "id", 10L);
        return order;
    }

    // ===== 주문 생성 (총액 계산) =====

    private void givenCustomerAndMenu(Menu menu) {
        given(userRepository.findByUsername("customer1")).willReturn(Optional.of(customer));
        given(menuRepository.findByIdAndDeletedFalse(1L)).willReturn(Optional.of(menu));
    }

    @Test
    void 총액은_메뉴_가격_곱하기_수량으로_계산한다() {
        givenCustomerAndMenu(new Menu("후라이드 치킨", 18000, "바삭한 치킨", owner));
        given(orderRepository.save(any(Order.class))).willAnswer(invocation -> invocation.getArgument(0));

        OrderResponse response = orderService.createOrder(new OrderCreateRequest(1L, 2, "서울시"), "customer1");

        assertThat(response.totalPrice()).isEqualTo(36000);
        assertThat(response.status()).isEqualTo(OrderStatus.ORDER_REQUEST);
    }

    @Test
    void int_범위를_넘는_총액도_정확히_계산한다() {
        // 1,000,000,000원 × 3 = 3,000,000,000원 → int였다면 넘쳐서 400이던 금액
        givenCustomerAndMenu(new Menu("단체 주문", 1_000_000_000L, "대량 주문", owner));
        given(orderRepository.save(any(Order.class))).willAnswer(invocation -> invocation.getArgument(0));

        OrderResponse response = orderService.createOrder(new OrderCreateRequest(1L, 3, "서울시"), "customer1");

        assertThat(response.totalPrice()).isEqualTo(3_000_000_000L);
    }

    // 수량 최대값(Integer.MAX_VALUE = 2^31 - 1)에서 long에 딱 들어가는 가격의 경계
    //   (2^32 + 2) × (2^31 - 1) = 2^63 - 2          ≤ Long.MAX_VALUE → 성공
    //   (2^32 + 3) × (2^31 - 1) = 2^63 + 2^31 - 3   > Long.MAX_VALUE → 400
    @Test
    void 최대_수량에서_long_최대값_직전까지는_계산된다() {
        givenCustomerAndMenu(new Menu("경계 메뉴", 4_294_967_298L, "경계값", owner));
        given(orderRepository.save(any(Order.class))).willAnswer(invocation -> invocation.getArgument(0));

        OrderResponse response = orderService.createOrder(
                new OrderCreateRequest(1L, Integer.MAX_VALUE, "서울시"), "customer1");

        assertThat(response.totalPrice()).isEqualTo(Long.MAX_VALUE - 1);
    }

    @Test
    void 최대_수량에서_long_최대값을_1원이라도_넘으면_400() {
        givenCustomerAndMenu(new Menu("경계 메뉴", 4_294_967_299L, "경계값", owner));

        assertThatThrownBy(() -> orderService.createOrder(
                new OrderCreateRequest(1L, Integer.MAX_VALUE, "서울시"), "customer1"))
                .isInstanceOf(ApiException.class)
                .extracting("status").isEqualTo(HttpStatus.BAD_REQUEST);
        verify(orderRepository, never()).save(any());
    }

    @Test
    void 가격이_long_최대값이면_수량_1까지만_주문된다() {
        givenCustomerAndMenu(new Menu("경계 메뉴", Long.MAX_VALUE, "경계값", owner));
        given(orderRepository.save(any(Order.class))).willAnswer(invocation -> invocation.getArgument(0));

        assertThat(orderService.createOrder(new OrderCreateRequest(1L, 1, "서울시"), "customer1").totalPrice())
                .isEqualTo(Long.MAX_VALUE);
        assertThatThrownBy(() -> orderService.createOrder(new OrderCreateRequest(1L, 2, "서울시"), "customer1"))
                .isInstanceOf(ApiException.class)
                .extracting("status").isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void long_범위를_넘는_총액은_400() {
        givenCustomerAndMenu(new Menu("단체 주문", Long.MAX_VALUE / 2 + 1, "대량 주문", owner));

        assertThatThrownBy(() -> orderService.createOrder(new OrderCreateRequest(1L, 2, "서울시"), "customer1"))
                .isInstanceOf(ApiException.class)
                .hasMessage("주문 금액이 너무 큽니다. 수량을 줄여주세요.")
                .extracting("status").isEqualTo(HttpStatus.BAD_REQUEST);
        verify(orderRepository, never()).save(any());
    }

    // ===== 주문 취소 =====

    @Test
    void 본인의_주문요청_주문을_취소한다() {
        Order order = order(OrderStatus.ORDER_REQUEST);
        given(userRepository.findByUsername("customer1")).willReturn(Optional.of(customer));
        given(orderRepository.findById(10L)).willReturn(Optional.of(order));

        orderService.cancelOrder(10L, "customer1");

        assertThat(order.getStatus()).isEqualTo(OrderStatus.ORDER_CANCELLED);
    }

    @Test
    void 결제완료된_주문은_취소할_수_없다() {
        Order order = order(OrderStatus.PAYMENT_COMPLETED);
        given(userRepository.findByUsername("customer1")).willReturn(Optional.of(customer));
        given(orderRepository.findById(10L)).willReturn(Optional.of(order));

        assertThatThrownBy(() -> orderService.cancelOrder(10L, "customer1"))
                .isInstanceOf(InvalidOrderStatusException.class)
                .hasMessage("주문요청 상태에서만 주문을 취소할 수 있습니다.");
        assertThat(order.getStatus()).isEqualTo(OrderStatus.PAYMENT_COMPLETED);
    }

    @Test
    void 다른_고객의_주문은_취소할_수_없다() {
        Order order = order(OrderStatus.ORDER_REQUEST);
        given(userRepository.findByUsername("customer2")).willReturn(Optional.of(otherCustomer));
        given(orderRepository.findById(10L)).willReturn(Optional.of(order));

        assertThatThrownBy(() -> orderService.cancelOrder(10L, "customer2"))
                .isInstanceOf(ApiException.class)
                .extracting("status").isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(order.getStatus()).isEqualTo(OrderStatus.ORDER_REQUEST);
    }

    @Test
    void OWNER는_서비스에서도_취소가_막힌다() {
        // SecurityConfig를 통과했더라도(토큰 발급 후 역할 변경 등) Service에서 한 번 더 확인
        given(userRepository.findByUsername("owner1")).willReturn(Optional.of(owner));

        assertThatThrownBy(() -> orderService.cancelOrder(10L, "owner1"))
                .isInstanceOf(ApiException.class)
                .extracting("status").isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void 없는_주문은_취소할_수_없다() {
        given(userRepository.findByUsername("customer1")).willReturn(Optional.of(customer));
        given(orderRepository.findById(99L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> orderService.cancelOrder(99L, "customer1"))
                .isInstanceOf(ApiException.class)
                .extracting("status").isEqualTo(HttpStatus.NOT_FOUND);
    }

    // ===== 주문 상태 변경 (OWNER) =====

    @Test
    void 결제완료_주문을_수락한다() {
        Order order = order(OrderStatus.PAYMENT_COMPLETED);
        given(userRepository.findByUsername("owner1")).willReturn(Optional.of(owner));
        given(orderRepository.findById(10L)).willReturn(Optional.of(order));

        orderService.statusOrder(10L, "owner1", OrderStatus.ORDER_ACCEPTED);

        assertThat(order.getStatus()).isEqualTo(OrderStatus.ORDER_ACCEPTED);
    }

    @Test
    void 수락된_주문을_배달완료한다() {
        Order order = order(OrderStatus.ORDER_ACCEPTED);
        given(userRepository.findByUsername("owner1")).willReturn(Optional.of(owner));
        given(orderRepository.findById(10L)).willReturn(Optional.of(order));

        orderService.statusOrder(10L, "owner1", OrderStatus.DELIVERY_COMPLETED);

        assertThat(order.getStatus()).isEqualTo(OrderStatus.DELIVERY_COMPLETED);
    }

    @Test
    void 결제_전_주문은_수락할_수_없다() {
        Order order = order(OrderStatus.ORDER_REQUEST);
        given(userRepository.findByUsername("owner1")).willReturn(Optional.of(owner));
        given(orderRepository.findById(10L)).willReturn(Optional.of(order));

        assertThatThrownBy(() -> orderService.statusOrder(10L, "owner1", OrderStatus.ORDER_ACCEPTED))
                .isInstanceOf(InvalidOrderStatusException.class);
        assertThat(order.getStatus()).isEqualTo(OrderStatus.ORDER_REQUEST);
    }

    @Test
    void OWNER가_요청할_수_없는_상태로는_변경할_수_없다() {
        // 주문취소·결제완료·주문요청은 OWNER의 상태 변경 API로 요청할 수 없음
        Order order = order(OrderStatus.PAYMENT_COMPLETED);
        given(userRepository.findByUsername("owner1")).willReturn(Optional.of(owner));
        given(orderRepository.findById(10L)).willReturn(Optional.of(order));

        assertThatThrownBy(() -> orderService.statusOrder(10L, "owner1", OrderStatus.ORDER_CANCELLED))
                .isInstanceOf(InvalidOrderStatusException.class)
                .hasMessage("허용되지 않는 주문 상태 변경입니다.");
        assertThat(order.getStatus()).isEqualTo(OrderStatus.PAYMENT_COMPLETED);
    }

    @Test
    void 다른_OWNER의_메뉴에_들어온_주문은_변경할_수_없다() {
        Order order = order(OrderStatus.PAYMENT_COMPLETED);
        given(userRepository.findByUsername("owner2")).willReturn(Optional.of(otherOwner));
        given(orderRepository.findById(10L)).willReturn(Optional.of(order));

        assertThatThrownBy(() -> orderService.statusOrder(10L, "owner2", OrderStatus.ORDER_ACCEPTED))
                .isInstanceOf(ApiException.class)
                .extracting("status").isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(order.getStatus()).isEqualTo(OrderStatus.PAYMENT_COMPLETED);
    }

    @Test
    void CUSTOMER는_서비스에서도_상태_변경이_막힌다() {
        given(userRepository.findByUsername("customer1")).willReturn(Optional.of(customer));

        assertThatThrownBy(() -> orderService.statusOrder(10L, "customer1", OrderStatus.ORDER_ACCEPTED))
                .isInstanceOf(ApiException.class)
                .extracting("status").isEqualTo(HttpStatus.FORBIDDEN);
    }
}
