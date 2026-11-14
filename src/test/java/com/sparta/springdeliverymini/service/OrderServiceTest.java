package com.sparta.springdeliverymini.service;

import com.sparta.springdeliverymini.dto.OrderCreateRequest;
import com.sparta.springdeliverymini.dto.OrderItemRequest;
import com.sparta.springdeliverymini.dto.OrderItemResponse;
import com.sparta.springdeliverymini.dto.OrderResponse;
import com.sparta.springdeliverymini.dto.PageQuery;
import com.sparta.springdeliverymini.entity.*;
import com.sparta.springdeliverymini.exception.ApiException;
import com.sparta.springdeliverymini.exception.InvalidOrderStatusException;
import com.sparta.springdeliverymini.repository.MenuRepository;
import com.sparta.springdeliverymini.repository.OrderRepository;
import com.sparta.springdeliverymini.repository.PaymentRepository;
import com.sparta.springdeliverymini.repository.UserRepository;
import com.sparta.springdeliverymini.exception.InvalidPaymentStatusException;
import java.math.BigDecimal;
import com.sparta.springdeliverymini.config.TimeConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.domain.SliceImpl;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.groups.Tuple.tuple;
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

    @Mock
    private StoreService storeService;

    @Mock
    private PaymentRepository paymentRepository;

    // 배달완료 시각을 고정해 deliveredAt이 Clock 기준으로 기록되는지 확인
    private static final LocalDateTime FIXED_NOW = LocalDateTime.of(2026, 11, 5, 23, 59, 59);
    private final Clock fixedClock = Clock.fixed(FIXED_NOW.atZone(TimeConfig.ZONE).toInstant(), TimeConfig.ZONE);

    private OrderService orderService;

    // Clock은 Mock이 아니라 고정 값이므로 @InjectMocks 대신 직접 생성 (PaymentServiceTest와 같은 방식)
    @BeforeEach
    void setUp() {
        orderService = new OrderService(orderRepository, menuRepository, userRepository, storeService,
                paymentRepository, fixedClock);
    }

    private final User customer = user(1L, "customer1", Role.CUSTOMER);
    private final User otherCustomer = user(2L, "customer2", Role.CUSTOMER);
    private final User owner = user(3L, "owner1", Role.OWNER);
    private final User otherOwner = user(4L, "owner2", Role.OWNER);
    private final Store store = store(20L, "치킨집", owner);

    // id는 DB가 생성하므로 테스트에서는 리플렉션으로 직접 지정
    private static User user(Long id, String username, Role role) {
        User user = new User(username, "encoded-password", role);
        ReflectionTestUtils.setField(user, "id", id);
        return user;
    }

    private static Store store(Long id, String name, User owner) {
        Store store = new Store(name, owner);
        ReflectionTestUtils.setField(store, "id", id);
        return store;
    }

    private static Menu menu(Long id, String name, long price, Store store) {
        Menu menu = new Menu(name, price, "설명", store);
        ReflectionTestUtils.setField(menu, "id", id);
        return menu;
    }

    // customer가 owner의 매장(store) 메뉴를 주문한 상태
    private Order order(OrderStatus status) {
        Order order = new Order(customer, store, "서울시", status);
        order.addItem(menu(1L, "후라이드 치킨", 18000, store), 1);
        ReflectionTestUtils.setField(order, "id", 10L);
        return order;
    }

    private static OrderCreateRequest request(OrderItemRequest... items) {
        return new OrderCreateRequest(List.of(items), "서울시");
    }

    private static OrderItemRequest item(Long menuId, int quantity) {
        return new OrderItemRequest(menuId, quantity);
    }

    // ===== 주문 생성 (총액 계산) =====

    private void givenCustomerAndMenus(Menu... menus) {
        given(userRepository.findByUsername("customer1")).willReturn(Optional.of(customer));
        given(menuRepository.findAllByIdInAndDeletedFalse(any())).willReturn(List.of(menus));
    }

    private void givenSaveReturnsArgument() {
        given(orderRepository.save(any(Order.class))).willAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void 총액은_메뉴_가격_곱하기_수량으로_계산한다() {
        givenCustomerAndMenus(menu(1L, "후라이드 치킨", 18000, store));
        givenSaveReturnsArgument();

        OrderResponse response = orderService.createOrder(request(item(1L, 2)), "customer1");

        assertThat(response.totalPrice()).isEqualTo(36000);
        assertThat(response.status()).isEqualTo(OrderStatus.ORDER_REQUEST);
    }

    @Test
    void 여러_메뉴를_담으면_총액은_항목_금액의_합이다() {
        givenCustomerAndMenus(menu(1L, "후라이드 치킨", 18000, store), menu(2L, "콜라", 2000, store));
        givenSaveReturnsArgument();

        OrderResponse response = orderService.createOrder(request(item(1L, 2), item(2L, 3)), "customer1");

        assertThat(response.totalPrice()).isEqualTo(18000 * 2 + 2000 * 3);
        assertThat(response.items()).extracting(OrderItemResponse::menuName, OrderItemResponse::linePrice)
                .containsExactly(tuple("후라이드 치킨", 36000L), tuple("콜라", 6000L)); // 요청한 순서대로
    }

    @Test
    void 주문은_메뉴의_매장으로_연결된다() {
        givenCustomerAndMenus(menu(1L, "후라이드 치킨", 18000, store));
        givenSaveReturnsArgument();

        OrderResponse response = orderService.createOrder(request(item(1L, 1)), "customer1");

        assertThat(response.storeId()).isEqualTo(20L);
        assertThat(response.storeName()).isEqualTo("치킨집");
    }

    @Test
    void 다른_매장의_메뉴를_함께_담으면_400() {
        Store pizzaStore = store(21L, "피자집", owner);
        givenCustomerAndMenus(menu(1L, "후라이드 치킨", 18000, store), menu(2L, "피자", 20000, pizzaStore));

        assertThatThrownBy(() -> orderService.createOrder(request(item(1L, 1), item(2L, 1)), "customer1"))
                .isInstanceOf(ApiException.class)
                .hasMessage("한 주문에는 같은 매장의 메뉴만 담을 수 있습니다.")
                .extracting("status").isEqualTo(HttpStatus.BAD_REQUEST);
        verify(orderRepository, never()).save(any());
    }

    @Test
    void 같은_메뉴를_여러_항목으로_나눠_담으면_400() {
        given(userRepository.findByUsername("customer1")).willReturn(Optional.of(customer));

        assertThatThrownBy(() -> orderService.createOrder(request(item(1L, 1), item(1L, 2)), "customer1"))
                .isInstanceOf(ApiException.class)
                .extracting("status").isEqualTo(HttpStatus.BAD_REQUEST);
        verify(menuRepository, never()).findAllByIdInAndDeletedFalse(any());
        verify(orderRepository, never()).save(any());
    }

    @Test
    void 항목_중_하나라도_없거나_삭제된_메뉴면_404() {
        // 2번 메뉴는 조회 결과에 없음 (없거나 삭제됨)
        givenCustomerAndMenus(menu(1L, "후라이드 치킨", 18000, store));

        assertThatThrownBy(() -> orderService.createOrder(request(item(1L, 1), item(2L, 1)), "customer1"))
                .isInstanceOf(ApiException.class)
                .extracting("status").isEqualTo(HttpStatus.NOT_FOUND);
        verify(orderRepository, never()).save(any());
    }

    @Test
    void int_범위를_넘는_총액도_정확히_계산한다() {
        // 1,000,000,000원 × 3 = 3,000,000,000원 → int였다면 넘쳐서 400이던 금액
        givenCustomerAndMenus(menu(1L, "단체 주문", 1_000_000_000L, store));
        givenSaveReturnsArgument();

        OrderResponse response = orderService.createOrder(request(item(1L, 3)), "customer1");

        assertThat(response.totalPrice()).isEqualTo(3_000_000_000L);
    }

    // 수량 최대값(Integer.MAX_VALUE = 2^31 - 1)에서 long에 딱 들어가는 가격의 경계
    //   (2^32 + 2) × (2^31 - 1) = 2^63 - 2          ≤ Long.MAX_VALUE → 성공
    //   (2^32 + 3) × (2^31 - 1) = 2^63 + 2^31 - 3   > Long.MAX_VALUE → 400
    @Test
    void 최대_수량에서_long_최대값_직전까지는_계산된다() {
        givenCustomerAndMenus(menu(1L, "경계 메뉴", 4_294_967_298L, store));
        givenSaveReturnsArgument();

        OrderResponse response = orderService.createOrder(request(item(1L, Integer.MAX_VALUE)), "customer1");

        assertThat(response.totalPrice()).isEqualTo(Long.MAX_VALUE - 1);
    }

    @Test
    void 최대_수량에서_long_최대값을_1원이라도_넘으면_400() {
        givenCustomerAndMenus(menu(1L, "경계 메뉴", 4_294_967_299L, store));

        assertThatThrownBy(() -> orderService.createOrder(request(item(1L, Integer.MAX_VALUE)), "customer1"))
                .isInstanceOf(ApiException.class)
                .extracting("status").isEqualTo(HttpStatus.BAD_REQUEST);
        verify(orderRepository, never()).save(any());
    }

    @Test
    void 가격이_long_최대값이면_수량_1까지만_주문된다() {
        givenCustomerAndMenus(menu(1L, "경계 메뉴", Long.MAX_VALUE, store));
        givenSaveReturnsArgument();

        assertThat(orderService.createOrder(request(item(1L, 1)), "customer1").totalPrice())
                .isEqualTo(Long.MAX_VALUE);
        assertThatThrownBy(() -> orderService.createOrder(request(item(1L, 2)), "customer1"))
                .isInstanceOf(ApiException.class)
                .extracting("status").isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void long_범위를_넘는_총액은_400() {
        givenCustomerAndMenus(menu(1L, "단체 주문", Long.MAX_VALUE / 2 + 1, store));

        assertThatThrownBy(() -> orderService.createOrder(request(item(1L, 2)), "customer1"))
                .isInstanceOf(ApiException.class)
                .hasMessage("주문 금액이 너무 큽니다. 수량을 줄여주세요.")
                .extracting("status").isEqualTo(HttpStatus.BAD_REQUEST);
        verify(orderRepository, never()).save(any());
    }

    @Test
    void 항목별로는_범위_안이어도_합계가_long을_넘으면_400() {
        givenCustomerAndMenus(menu(1L, "비싼 메뉴", Long.MAX_VALUE, store), menu(2L, "콜라", 1, store));

        assertThatThrownBy(() -> orderService.createOrder(request(item(1L, 1), item(2L, 1)), "customer1"))
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
        given(orderRepository.findByIdForUpdate(10L)).willReturn(Optional.of(order));

        orderService.cancelOrder(10L, "customer1");

        assertThat(order.getStatus()).isEqualTo(OrderStatus.ORDER_CANCELLED);
        verify(paymentRepository, never()).findByOrderId(any()); // 결제 전 주문은 결제 조회 안 함
    }

    @Test
    void 결제완료_주문을_취소하면_결제도_함께_취소된다() {
        Order order = order(OrderStatus.PAYMENT_COMPLETED);
        Payment payment = new Payment(order, 18000, new BigDecimal("0.05"), PaymentMethod.CARD, FIXED_NOW.minusHours(1));
        given(userRepository.findByUsername("customer1")).willReturn(Optional.of(customer));
        given(orderRepository.findByIdForUpdate(10L)).willReturn(Optional.of(order));
        given(paymentRepository.findByOrderId(10L)).willReturn(Optional.of(payment));

        orderService.cancelOrder(10L, "customer1");

        assertThat(order.getStatus()).isEqualTo(OrderStatus.ORDER_CANCELLED);
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.CANCELLED);
        assertThat(payment.getCancelledAt()).isEqualTo(FIXED_NOW); // Clock 기준
        assertThat(payment.getAmount()).isEqualTo(18000); // 금액은 그대로 (전액 취소)
    }

    @Test
    void 이미_취소된_결제면_409() {
        Order order = order(OrderStatus.PAYMENT_COMPLETED);
        Payment payment = new Payment(order, 18000, new BigDecimal("0.05"), PaymentMethod.CARD, FIXED_NOW.minusHours(1));
        payment.cancel(FIXED_NOW.minusMinutes(1));
        given(userRepository.findByUsername("customer1")).willReturn(Optional.of(customer));
        given(orderRepository.findByIdForUpdate(10L)).willReturn(Optional.of(order));
        given(paymentRepository.findByOrderId(10L)).willReturn(Optional.of(payment));

        assertThatThrownBy(() -> orderService.cancelOrder(10L, "customer1"))
                .isInstanceOf(InvalidPaymentStatusException.class);
    }

    @ParameterizedTest
    @EnumSource(value = OrderStatus.class, names = {"ORDER_ACCEPTED", "DELIVERY_COMPLETED", "ORDER_CANCELLED"})
    void 주문수락_이후나_이미_취소된_주문은_취소할_수_없다(OrderStatus status) {
        Order order = order(status);
        given(userRepository.findByUsername("customer1")).willReturn(Optional.of(customer));
        given(orderRepository.findByIdForUpdate(10L)).willReturn(Optional.of(order));

        assertThatThrownBy(() -> orderService.cancelOrder(10L, "customer1"))
                .isInstanceOf(InvalidOrderStatusException.class)
                .hasMessage("주문수락 전(주문요청·결제완료) 상태에서만 주문을 취소할 수 있습니다.");
        assertThat(order.getStatus()).isEqualTo(status);
        verify(paymentRepository, never()).findByOrderId(any());
    }

    @Test
    void 다른_고객의_주문은_취소할_수_없다() {
        Order order = order(OrderStatus.ORDER_REQUEST);
        given(userRepository.findByUsername("customer2")).willReturn(Optional.of(otherCustomer));
        given(orderRepository.findByIdForUpdate(10L)).willReturn(Optional.of(order));

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
        given(orderRepository.findByIdForUpdate(99L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> orderService.cancelOrder(99L, "customer1"))
                .isInstanceOf(ApiException.class)
                .extracting("status").isEqualTo(HttpStatus.NOT_FOUND);
    }

    // ===== 주문 상태 변경 (OWNER) =====

    @Test
    void 결제완료_주문을_수락한다() {
        Order order = order(OrderStatus.PAYMENT_COMPLETED);
        given(userRepository.findByUsername("owner1")).willReturn(Optional.of(owner));
        given(orderRepository.findByIdForUpdate(10L)).willReturn(Optional.of(order));

        orderService.statusOrder(10L, "owner1", OrderStatus.ORDER_ACCEPTED);

        assertThat(order.getStatus()).isEqualTo(OrderStatus.ORDER_ACCEPTED);
    }

    @Test
    void 수락된_주문을_배달완료한다() {
        Order order = order(OrderStatus.ORDER_ACCEPTED);
        given(userRepository.findByUsername("owner1")).willReturn(Optional.of(owner));
        given(orderRepository.findByIdForUpdate(10L)).willReturn(Optional.of(order));

        orderService.statusOrder(10L, "owner1", OrderStatus.DELIVERY_COMPLETED);

        assertThat(order.getStatus()).isEqualTo(OrderStatus.DELIVERY_COMPLETED);
        assertThat(order.getDeliveredAt()).isEqualTo(FIXED_NOW); // JVM 시간대가 아닌 Clock(Asia/Seoul) 기준
    }

    @Test
    void 결제_전_주문은_수락할_수_없다() {
        Order order = order(OrderStatus.ORDER_REQUEST);
        given(userRepository.findByUsername("owner1")).willReturn(Optional.of(owner));
        given(orderRepository.findByIdForUpdate(10L)).willReturn(Optional.of(order));

        assertThatThrownBy(() -> orderService.statusOrder(10L, "owner1", OrderStatus.ORDER_ACCEPTED))
                .isInstanceOf(InvalidOrderStatusException.class);
        assertThat(order.getStatus()).isEqualTo(OrderStatus.ORDER_REQUEST);
    }

    @Test
    void OWNER가_요청할_수_없는_상태로는_변경할_수_없다() {
        // 주문취소·결제완료·주문요청은 OWNER의 상태 변경 API로 요청할 수 없음
        Order order = order(OrderStatus.PAYMENT_COMPLETED);
        given(userRepository.findByUsername("owner1")).willReturn(Optional.of(owner));
        given(orderRepository.findByIdForUpdate(10L)).willReturn(Optional.of(order));

        assertThatThrownBy(() -> orderService.statusOrder(10L, "owner1", OrderStatus.ORDER_CANCELLED))
                .isInstanceOf(InvalidOrderStatusException.class)
                .hasMessage("허용되지 않는 주문 상태 변경입니다.");
        assertThat(order.getStatus()).isEqualTo(OrderStatus.PAYMENT_COMPLETED);
    }

    @Test
    void 다른_OWNER의_매장에_들어온_주문은_변경할_수_없다() {
        Order order = order(OrderStatus.PAYMENT_COMPLETED);
        given(userRepository.findByUsername("owner2")).willReturn(Optional.of(otherOwner));
        given(orderRepository.findByIdForUpdate(10L)).willReturn(Optional.of(order));

        assertThatThrownBy(() -> orderService.statusOrder(10L, "owner2", OrderStatus.ORDER_ACCEPTED))
                .isInstanceOf(ApiException.class)
                .extracting("status").isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(order.getStatus()).isEqualTo(OrderStatus.PAYMENT_COMPLETED);
    }

    @Test
    void 같은_OWNER의_다른_매장_주문도_처리할_수_있다() {
        // 소유권은 매장 단위로 판단 — owner가 가진 두 번째 매장의 주문
        Store secondStore = store(21L, "피자집", owner);
        Order order = new Order(customer, secondStore, "서울시", OrderStatus.PAYMENT_COMPLETED);
        order.addItem(menu(2L, "피자", 20000, secondStore), 1);
        given(userRepository.findByUsername("owner1")).willReturn(Optional.of(owner));
        given(orderRepository.findByIdForUpdate(10L)).willReturn(Optional.of(order));

        orderService.statusOrder(10L, "owner1", OrderStatus.ORDER_ACCEPTED);

        assertThat(order.getStatus()).isEqualTo(OrderStatus.ORDER_ACCEPTED);
    }

    @Test
    void CUSTOMER는_서비스에서도_상태_변경이_막힌다() {
        given(userRepository.findByUsername("customer1")).willReturn(Optional.of(customer));

        assertThatThrownBy(() -> orderService.statusOrder(10L, "customer1", OrderStatus.ORDER_ACCEPTED))
                .isInstanceOf(ApiException.class)
                .extracting("status").isEqualTo(HttpStatus.FORBIDDEN);
    }

    // ===== 주문 목록 조회 =====

    // 목록 조회의 기본 페이지 (첫 페이지, 20건)
    private static final PageQuery PAGE = PageQuery.of(0, PageQuery.DEFAULT_SIZE);

    @Test
    void OWNER는_본인의_모든_매장_주문을_조회한다() {
        given(userRepository.findByUsername("owner1")).willReturn(Optional.of(owner));
        given(orderRepository.findAllByStoreOwnerId(3L, PAGE.toPageable()))
                .willReturn(new SliceImpl<>(List.of(order(OrderStatus.ORDER_REQUEST))));

        assertThat(orderService.getOrders("owner1", PAGE)).hasSize(1);
    }

    @Test
    void 주문_목록은_요청한_페이지를_id_오름차순으로_조회한다() {
        // 정렬 기준이 고유(id)해야 페이지 사이에 같은 주문이 중복·누락되지 않음
        given(userRepository.findByUsername("customer1")).willReturn(Optional.of(customer));
        Pageable expected = PageRequest.of(2, 50, Sort.by(Sort.Direction.ASC, "id"));
        given(orderRepository.findAllByUserId(customer.getId(), expected))
                .willReturn(new SliceImpl<>(List.of(order(OrderStatus.ORDER_REQUEST)), expected, true));

        Slice<OrderResponse> orders = orderService.getOrders("customer1", PageQuery.of(2, 50));

        assertThat(orders.getContent()).hasSize(1);
        assertThat(orders.hasNext()).isTrue();
    }

    // ===== 매장별 주문 조회 =====

    @Test
    void 본인_매장의_주문을_조회한다() {
        given(userRepository.findByUsername("owner1")).willReturn(Optional.of(owner));
        given(storeService.getOwnedStore(20L, owner)).willReturn(store);
        given(orderRepository.findAllByStoreId(20L, PAGE.toPageable()))
                .willReturn(new SliceImpl<>(List.of(order(OrderStatus.ORDER_REQUEST))));

        List<OrderResponse> orders = orderService.getStoreOrders(20L, "owner1", PAGE).getContent();

        assertThat(orders).hasSize(1);
        assertThat(orders.get(0).storeId()).isEqualTo(20L);
    }

    @Test
    void 다른_OWNER의_매장_주문은_조회할_수_없다() {
        // 소유권 검증(403)은 StoreService.getOwnedStore가 담당 → 예외가 그대로 전달되고 주문은 조회하지 않음
        given(userRepository.findByUsername("owner2")).willReturn(Optional.of(otherOwner));
        given(storeService.getOwnedStore(20L, otherOwner))
                .willThrow(new ApiException(HttpStatus.FORBIDDEN, "본인 매장이 아닙니다."));

        assertThatThrownBy(() -> orderService.getStoreOrders(20L, "owner2", PAGE))
                .isInstanceOf(ApiException.class)
                .extracting("status").isEqualTo(HttpStatus.FORBIDDEN);
        verify(orderRepository, never()).findAllByStoreId(any(), any());
    }

    @Test
    void CUSTOMER는_서비스에서도_매장_주문_조회가_막힌다() {
        given(userRepository.findByUsername("customer1")).willReturn(Optional.of(customer));

        assertThatThrownBy(() -> orderService.getStoreOrders(20L, "customer1", PAGE))
                .isInstanceOf(ApiException.class)
                .extracting("status").isEqualTo(HttpStatus.FORBIDDEN);
        verify(storeService, never()).getOwnedStore(any(), any());
    }
}
