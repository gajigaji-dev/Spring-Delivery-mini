package com.sparta.springdeliverymini.service;

import com.sparta.springdeliverymini.dto.PaymentRequest;
import com.sparta.springdeliverymini.dto.PaymentResponse;
import com.sparta.springdeliverymini.entity.*;
import com.sparta.springdeliverymini.exception.ApiException;
import com.sparta.springdeliverymini.exception.InvalidOrderStatusException;
import com.sparta.springdeliverymini.repository.OrderRepository;
import com.sparta.springdeliverymini.repository.PaymentRepository;
import com.sparta.springdeliverymini.repository.UserRepository;
import com.sparta.springdeliverymini.config.PaymentFeeProperties;
import com.sparta.springdeliverymini.config.TimeConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

// PaymentService가 결제 검증 후 Order.pay()로 상태를 바꾸고, 중복 결제를 409로 막는지 검증
// Repository는 Mock으로 대체 → DB 없이 실행
@ExtendWith(MockitoExtension.class)
class PaymentServiceTest {

    @Mock
    private PaymentRepository paymentRepository;

    @Mock
    private OrderRepository orderRepository;

    @Mock
    private UserRepository userRepository;

    // 결제 시각을 고정해 paidAt이 Clock 기준으로 기록되는지 확인 (Asia/Seoul 기준 자정 직전)
    private static final LocalDateTime FIXED_NOW = LocalDateTime.of(2026, 11, 5, 23, 59, 59);
    private final Clock fixedClock = Clock.fixed(FIXED_NOW.atZone(TimeConfig.ZONE).toInstant(), TimeConfig.ZONE);

    // 수수료율 5% (application.yml 기본값과 같음)
    private final PaymentFeeProperties feeProperties = new PaymentFeeProperties(new BigDecimal("0.05"));

    private PaymentService paymentService;

    // Clock·수수료율은 Mock이 아니라 고정 값이므로 @InjectMocks 대신 직접 생성
    @BeforeEach
    void setUp() {
        paymentService = new PaymentService(paymentRepository, orderRepository, userRepository, fixedClock, feeProperties);
    }

    private final User customer = user(1L, "customer1", Role.CUSTOMER);
    private final User otherCustomer = user(2L, "customer2", Role.CUSTOMER);
    private final User owner = user(3L, "owner1", Role.OWNER);

    // id는 DB가 생성하므로 테스트에서는 리플렉션으로 직접 지정
    private static User user(Long id, String username, Role role) {
        User user = new User(username, "encoded-password", role);
        ReflectionTestUtils.setField(user, "id", id);
        return user;
    }

    // customer가 owner의 메뉴를 2개 주문한 상태 (총액 36000원)
    private Order order(OrderStatus status) {
        Menu menu = new Menu("후라이드 치킨", 18000, "바삭한 치킨", owner);
        Order order = new Order(menu, customer, 2, 36000, "서울시", status);
        ReflectionTestUtils.setField(order, "id", 10L);
        return order;
    }

    private void givenCustomerAndOrder(Order order) {
        given(userRepository.findByUsername("customer1")).willReturn(Optional.of(customer));
        given(orderRepository.findById(10L)).willReturn(Optional.of(order));
    }

    @Test
    void 주문요청_주문을_결제하면_결제완료로_바뀐다() {
        Order order = order(OrderStatus.ORDER_REQUEST);
        givenCustomerAndOrder(order);
        given(paymentRepository.existsByOrderId(10L)).willReturn(false);

        PaymentResponse response = paymentService.pay(new PaymentRequest(10L, "CARD"), "customer1");

        assertThat(order.getStatus()).isEqualTo(OrderStatus.PAYMENT_COMPLETED);
        assertThat(response.orderStatus()).isEqualTo(OrderStatus.PAYMENT_COMPLETED);
        assertThat(response.amount()).isEqualTo(36000); // 요청값이 아닌 주문 총액
        assertThat(response.paidAt()).isEqualTo(FIXED_NOW); // JVM 시간대가 아닌 Clock(Asia/Seoul) 기준
        verify(paymentRepository).saveAndFlush(any(Payment.class));
    }

    @Test
    void 결제하면_설정된_수수료율로_수수료와_정산액을_계산해_저장한다() {
        Order order = order(OrderStatus.ORDER_REQUEST);
        givenCustomerAndOrder(order);
        given(paymentRepository.existsByOrderId(10L)).willReturn(false);

        paymentService.pay(new PaymentRequest(10L, "CARD"), "customer1");

        ArgumentCaptor<Payment> captor = ArgumentCaptor.forClass(Payment.class);
        verify(paymentRepository).saveAndFlush(captor.capture());
        Payment saved = captor.getValue();
        assertThat(saved.getAmount()).isEqualTo(36000);
        assertThat(saved.getFeeRate()).isEqualByComparingTo("0.05");
        assertThat(saved.getFeeAmount()).isEqualTo(1800);         // 36000 × 0.05
        assertThat(saved.getSettlementAmount()).isEqualTo(34200); // 36000 - 1800
    }

    @Test
    void int_범위를_넘는_주문_총액도_그대로_결제된다() {
        // 3,000,000,000원 — int 최대값(2,147,483,647)을 넘는 금액
        Menu menu = new Menu("단체 주문", 1_500_000_000L, "대량 주문", owner);
        Order order = new Order(menu, customer, 2, 3_000_000_000L, "서울시", OrderStatus.ORDER_REQUEST);
        ReflectionTestUtils.setField(order, "id", 10L);
        givenCustomerAndOrder(order);
        given(paymentRepository.existsByOrderId(10L)).willReturn(false);

        PaymentResponse response = paymentService.pay(new PaymentRequest(10L, "CARD"), "customer1");

        assertThat(response.amount()).isEqualTo(3_000_000_000L);
        ArgumentCaptor<Payment> captor = ArgumentCaptor.forClass(Payment.class);
        verify(paymentRepository).saveAndFlush(captor.capture());
        assertThat(captor.getValue().getFeeAmount()).isEqualTo(150_000_000L);
        assertThat(captor.getValue().getSettlementAmount()).isEqualTo(2_850_000_000L);
    }

    @Test
    void 이미_결제완료된_주문은_다시_결제할_수_없다() {
        Order order = order(OrderStatus.PAYMENT_COMPLETED);
        givenCustomerAndOrder(order);

        assertThatThrownBy(() -> paymentService.pay(new PaymentRequest(10L, "CARD"), "customer1"))
                .isInstanceOf(InvalidOrderStatusException.class)
                .hasMessage("주문요청 상태의 주문만 결제할 수 있습니다.");
        verify(paymentRepository, never()).saveAndFlush(any());
    }

    @Test
    void 취소된_주문은_결제할_수_없다() {
        Order order = order(OrderStatus.ORDER_CANCELLED);
        givenCustomerAndOrder(order);

        assertThatThrownBy(() -> paymentService.pay(new PaymentRequest(10L, "CARD"), "customer1"))
                .isInstanceOf(InvalidOrderStatusException.class);
        assertThat(order.getStatus()).isEqualTo(OrderStatus.ORDER_CANCELLED);
        verify(paymentRepository, never()).saveAndFlush(any());
    }

    @Test
    void 결제_내역이_이미_있으면_409() {
        // 주문 상태는 주문요청이지만 결제 기록이 남아 있는 경우 (데이터 불일치 대비)
        Order order = order(OrderStatus.ORDER_REQUEST);
        givenCustomerAndOrder(order);
        given(paymentRepository.existsByOrderId(10L)).willReturn(true);

        assertThatThrownBy(() -> paymentService.pay(new PaymentRequest(10L, "CARD"), "customer1"))
                .isInstanceOf(ApiException.class)
                .hasMessage("이미 결제된 주문입니다.")
                .extracting("status").isEqualTo(HttpStatus.CONFLICT);
        verify(paymentRepository, never()).saveAndFlush(any());
    }

    @Test
    void 동시_결제로_unique_제약을_위반하면_409() {
        // 6·7번 검사를 동시에 통과한 두 번째 요청 → DB unique 제약 위반
        Order order = order(OrderStatus.ORDER_REQUEST);
        givenCustomerAndOrder(order);
        given(paymentRepository.existsByOrderId(10L)).willReturn(false);
        given(paymentRepository.saveAndFlush(any(Payment.class)))
                .willThrow(new DataIntegrityViolationException("duplicate key"));

        assertThatThrownBy(() -> paymentService.pay(new PaymentRequest(10L, "CARD"), "customer1"))
                .isInstanceOf(ApiException.class)
                .hasMessage("이미 결제된 주문입니다.")
                .extracting("status").isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void 결제_수단이_CARD가_아니면_400() {
        given(userRepository.findByUsername("customer1")).willReturn(Optional.of(customer));

        assertThatThrownBy(() -> paymentService.pay(new PaymentRequest(10L, "CASH"), "customer1"))
                .isInstanceOf(ApiException.class)
                .extracting("status").isEqualTo(HttpStatus.BAD_REQUEST);
        verify(orderRepository, never()).findById(any());
    }

    @Test
    void 다른_고객의_주문은_결제할_수_없다() {
        Order order = order(OrderStatus.ORDER_REQUEST);
        given(userRepository.findByUsername("customer2")).willReturn(Optional.of(otherCustomer));
        given(orderRepository.findById(10L)).willReturn(Optional.of(order));

        assertThatThrownBy(() -> paymentService.pay(new PaymentRequest(10L, "CARD"), "customer2"))
                .isInstanceOf(ApiException.class)
                .extracting("status").isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(order.getStatus()).isEqualTo(OrderStatus.ORDER_REQUEST);
    }

    @Test
    void OWNER는_서비스에서도_결제가_막힌다() {
        given(userRepository.findByUsername("owner1")).willReturn(Optional.of(owner));

        assertThatThrownBy(() -> paymentService.pay(new PaymentRequest(10L, "CARD"), "owner1"))
                .isInstanceOf(ApiException.class)
                .extracting("status").isEqualTo(HttpStatus.FORBIDDEN);
    }
}
