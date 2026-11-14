package com.sparta.springdeliverymini.service;

import com.sparta.springdeliverymini.config.TimeConfig;
import com.sparta.springdeliverymini.dto.PaymentResponse;
import com.sparta.springdeliverymini.entity.*;
import com.sparta.springdeliverymini.exception.ApiException;
import com.sparta.springdeliverymini.exception.InvalidPaymentStatusException;
import com.sparta.springdeliverymini.repository.PaymentRepository;
import com.sparta.springdeliverymini.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

// AdminPaymentService — 관리자 확인, 행 잠금 조회, 환불 규칙 위반 시 409 (DB 없이)
@ExtendWith(MockitoExtension.class)
class AdminPaymentServiceTest {

    @Mock private PaymentRepository paymentRepository;
    @Mock private UserRepository userRepository;

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 7, 10, 0);
    private static final LocalDateTime DELIVERED_AT = LocalDateTime.of(2026, 11, 5, 12, 0);

    private AdminPaymentService service;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(NOW.atZone(TimeConfig.ZONE).toInstant(), TimeConfig.ZONE);
        service = new AdminPaymentService(paymentRepository, userRepository, clock);
    }

    private static Payment payment(OrderStatus orderStatus) {
        Store store = new Store("치킨집", new User("owner1", "pw", Role.OWNER));
        Order order = new Order(new User("customer1", "pw", Role.CUSTOMER), store, "서울시", orderStatus);
        ReflectionTestUtils.setField(order, "id", 10L);
        if (orderStatus == OrderStatus.DELIVERY_COMPLETED) {
            ReflectionTestUtils.setField(order, "deliveredAt", DELIVERED_AT);
        }
        Payment payment = new Payment(order, 18009, new BigDecimal("0.05"), PaymentMethod.CARD, DELIVERED_AT.minusHours(1));
        ReflectionTestUtils.setField(payment, "id", 1L);
        return payment;
    }

    private void givenUser(String username, Role role) {
        given(userRepository.findByUsername(username)).willReturn(Optional.of(new User(username, "pw", role)));
    }

    @Test
    void 관리자가_배달완료_주문의_결제를_전액_환불한다() {
        givenUser("admin", Role.ADMIN);
        Payment payment = payment(OrderStatus.DELIVERY_COMPLETED);
        given(paymentRepository.findByIdForUpdate(1L)).willReturn(Optional.of(payment));

        PaymentResponse response = service.refund(1L, "admin");

        assertThat(response.status()).isEqualTo(PaymentStatus.REFUNDED);
        assertThat(response.refundedAt()).isEqualTo(NOW); // Clock 기준
        assertThat(response.amount()).isEqualTo(18009);
        assertThat(response.orderStatus()).isEqualTo(OrderStatus.DELIVERY_COMPLETED); // 주문 상태는 그대로
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.REFUNDED);
    }

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void 환불하면_식별자와_상태_변경이_로그에_남고_개인정보는_남지_않는다(CapturedOutput output) {
        givenUser("admin", Role.ADMIN);
        given(paymentRepository.findByIdForUpdate(1L)).willReturn(Optional.of(payment(OrderStatus.DELIVERY_COMPLETED)));

        service.refund(1L, "admin");

        assertThat(output).contains("결제 환불 paymentId=1, orderId=10, amount=18009, status=COMPLETED->REFUNDED");
        // 회원 아이디·배달 주소는 로그에 남기지 않음
        assertThat(output).doesNotContain("customer1").doesNotContain("서울시");
    }

    @Test
    void 관리자가_아니면_403이고_결제를_조회하지_않는다() {
        for (Role role : new Role[]{Role.OWNER, Role.CUSTOMER}) {
            givenUser("user-" + role, role);
            assertThatThrownBy(() -> service.refund(1L, "user-" + role))
                    .isInstanceOf(ApiException.class).extracting("status").isEqualTo(HttpStatus.FORBIDDEN);
        }
        verify(paymentRepository, never()).findByIdForUpdate(anyLong());
    }

    @Test
    void 존재하지_않는_회원이면_401() {
        given(userRepository.findByUsername("ghost")).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.refund(1L, "ghost"))
                .isInstanceOf(ApiException.class).extracting("status").isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void 없는_결제면_404() {
        givenUser("admin", Role.ADMIN);
        given(paymentRepository.findByIdForUpdate(99L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.refund(99L, "admin"))
                .isInstanceOf(ApiException.class).extracting("status").isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void 배달_전_주문의_결제는_환불할_수_없다() {
        givenUser("admin", Role.ADMIN);
        Payment payment = payment(OrderStatus.ORDER_ACCEPTED);
        given(paymentRepository.findByIdForUpdate(1L)).willReturn(Optional.of(payment));

        assertThatThrownBy(() -> service.refund(1L, "admin")).isInstanceOf(InvalidPaymentStatusException.class);
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.COMPLETED);
    }

    @Test
    void 이미_환불된_결제는_409() {
        givenUser("admin", Role.ADMIN);
        Payment payment = payment(OrderStatus.DELIVERY_COMPLETED);
        payment.refund(NOW.minusDays(1));
        given(paymentRepository.findByIdForUpdate(1L)).willReturn(Optional.of(payment));

        assertThatThrownBy(() -> service.refund(1L, "admin")).isInstanceOf(InvalidPaymentStatusException.class);
        assertThat(payment.getRefundedAt()).isEqualTo(NOW.minusDays(1)); // 처음 환불 시각 유지
    }
}
