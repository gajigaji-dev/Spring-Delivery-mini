package com.sparta.springdeliverymini.service;

import com.sparta.springdeliverymini.dto.PaymentRequest;
import com.sparta.springdeliverymini.dto.PaymentResponse;
import com.sparta.springdeliverymini.entity.*;
import com.sparta.springdeliverymini.exception.ApiException;
import com.sparta.springdeliverymini.exception.InvalidOrderStatusException;
import com.sparta.springdeliverymini.repository.OrderRepository;
import com.sparta.springdeliverymini.repository.PaymentRepository;
import com.sparta.springdeliverymini.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

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

    @InjectMocks
    private PaymentService paymentService;

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
        verify(paymentRepository).saveAndFlush(any(Payment.class));
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
