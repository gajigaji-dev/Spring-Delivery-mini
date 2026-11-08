package com.sparta.springdeliverymini.service;

import com.sparta.springdeliverymini.config.TimeConfig;
import com.sparta.springdeliverymini.dto.SettlementItemResponse;
import com.sparta.springdeliverymini.dto.StoreSettlementResponse;
import com.sparta.springdeliverymini.entity.*;
import com.sparta.springdeliverymini.exception.ApiException;
import com.sparta.springdeliverymini.repository.PaymentRepository;
import com.sparta.springdeliverymini.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

// SettlementService가 권한·소유권·기간을 확인하고 합계를 올바르게 계산하는지 검증 (DB 없이)
@ExtendWith(MockitoExtension.class)
class SettlementServiceTest {

    @Mock
    private PaymentRepository paymentRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private StoreService storeService;

    // 11/6 09:00 → 11/5까지 확정
    private static final LocalDateTime FIXED_NOW = LocalDateTime.of(2026, 11, 6, 9, 0);
    private final Clock fixedClock = Clock.fixed(FIXED_NOW.atZone(TimeConfig.ZONE).toInstant(), TimeConfig.ZONE);

    private static final LocalDate FROM = LocalDate.of(2026, 11, 1);
    private static final LocalDate TO = LocalDate.of(2026, 11, 5);

    private SettlementService settlementService;

    private final User owner = user(1L, "owner1", Role.OWNER);
    private final User customer = user(2L, "customer1", Role.CUSTOMER);
    private final Store store = store(20L, "치킨집", owner);

    // Clock은 Mock이 아니라 고정 값이므로 @InjectMocks 대신 직접 생성
    @BeforeEach
    void setUp() {
        settlementService = new SettlementService(paymentRepository, userRepository, storeService, fixedClock);
    }

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

    // 배달완료된 주문의 결제 (수수료율 5%)
    private Payment deliveredPayment(Long id, long amount, LocalDateTime deliveredAt) {
        Order order = new Order(customer, store, "서울시", OrderStatus.ORDER_ACCEPTED);
        order.addItem(new Menu("치킨", amount, null, store), 1);
        order.complete(deliveredAt);
        ReflectionTestUtils.setField(order, "id", id);

        Payment payment = new Payment(order, amount, new BigDecimal("0.05"), PaymentMethod.CARD, deliveredAt.minusMinutes(30));
        ReflectionTestUtils.setField(payment, "id", id);
        return payment;
    }

    @Test
    void 기간의_결제를_합산하고_건별_내역을_함께_반환한다() {
        // 건별 수수료: 18,009 × 5% = 900.45 → 900원
        Payment first = deliveredPayment(1L, 18009, LocalDateTime.of(2026, 11, 1, 0, 0));
        Payment second = deliveredPayment(2L, 18009, LocalDateTime.of(2026, 11, 5, 23, 59, 59));
        given(userRepository.findByUsername("owner1")).willReturn(Optional.of(owner));
        given(storeService.getOwnedStore(20L, owner)).willReturn(store);
        given(paymentRepository.findSettlementTargets(
                20L, LocalDateTime.of(2026, 11, 1, 0, 0), LocalDateTime.of(2026, 11, 6, 0, 0)))
                .willReturn(List.of(first, second));

        StoreSettlementResponse response = settlementService.getStoreSettlement(20L, FROM, TO, "owner1");

        assertThat(response.storeId()).isEqualTo(20L);
        assertThat(response.storeName()).isEqualTo("치킨집");
        assertThat(response.from()).isEqualTo(FROM);
        assertThat(response.to()).isEqualTo(TO);
        assertThat(response.paymentCount()).isEqualTo(2);
        assertThat(response.totalAmount()).isEqualTo(36018L);
        // 합계 금액에 수수료율을 다시 곱하지 않고 건별 수수료를 더함
        // (36,018 × 5% = 1,800.9 → 1,801원이 아니라 900 + 900 = 1,800원)
        assertThat(response.totalFee()).isEqualTo(1800L);
        assertThat(response.settlementAmount()).isEqualTo(17109L + 17109L);
        assertThat(response.totalFee() + response.settlementAmount()).isEqualTo(response.totalAmount());

        assertThat(response.items()).extracting(SettlementItemResponse::paymentId).containsExactly(1L, 2L);
        SettlementItemResponse item = response.items().get(1);
        assertThat(item.orderId()).isEqualTo(2L);
        assertThat(item.amount()).isEqualTo(18009L);
        assertThat(item.feeRate()).isEqualByComparingTo("0.05");
        assertThat(item.feeAmount()).isEqualTo(900L);
        assertThat(item.settlementAmount()).isEqualTo(17109L);
        assertThat(item.deliveredAt()).isEqualTo(LocalDateTime.of(2026, 11, 5, 23, 59, 59));
    }

    @Test
    void 정산_대상이_없으면_합계는_0이다() {
        given(userRepository.findByUsername("owner1")).willReturn(Optional.of(owner));
        given(storeService.getOwnedStore(20L, owner)).willReturn(store);
        given(paymentRepository.findSettlementTargets(any(), any(), any())).willReturn(List.of());

        StoreSettlementResponse response = settlementService.getStoreSettlement(20L, FROM, TO, "owner1");

        assertThat(response.paymentCount()).isZero();
        assertThat(response.totalAmount()).isZero();
        assertThat(response.totalFee()).isZero();
        assertThat(response.settlementAmount()).isZero();
        assertThat(response.items()).isEmpty();
    }

    @Test
    void 존재하지_않는_회원이면_401() {
        given(userRepository.findByUsername("ghost")).willReturn(Optional.empty());

        assertThatThrownBy(() -> settlementService.getStoreSettlement(20L, FROM, TO, "ghost"))
                .isInstanceOf(ApiException.class)
                .extracting("status").isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void CUSTOMER는_403() {
        given(userRepository.findByUsername("customer1")).willReturn(Optional.of(customer));

        assertThatThrownBy(() -> settlementService.getStoreSettlement(20L, FROM, TO, "customer1"))
                .isInstanceOf(ApiException.class)
                .extracting("status").isEqualTo(HttpStatus.FORBIDDEN);
        verify(paymentRepository, never()).findSettlementTargets(anyLong(), any(), any());
    }

    @Test
    void 본인_매장이_아니면_StoreService의_403을_그대로_전달하고_조회하지_않는다() {
        given(userRepository.findByUsername("owner1")).willReturn(Optional.of(owner));
        given(storeService.getOwnedStore(30L, owner))
                .willThrow(new ApiException(HttpStatus.FORBIDDEN, "본인 매장이 아닙니다."));

        assertThatThrownBy(() -> settlementService.getStoreSettlement(30L, FROM, TO, "owner1"))
                .isInstanceOf(ApiException.class)
                .extracting("status").isEqualTo(HttpStatus.FORBIDDEN);
        verify(paymentRepository, never()).findSettlementTargets(anyLong(), any(), any());
    }

    @Test
    void 기간_규칙을_어기면_400이고_조회하지_않는다() {
        given(userRepository.findByUsername("owner1")).willReturn(Optional.of(owner));
        given(storeService.getOwnedStore(20L, owner)).willReturn(store);

        // 오늘(11/6)은 아직 확정 전
        assertThatThrownBy(() -> settlementService.getStoreSettlement(20L, FROM, FIXED_NOW.toLocalDate(), "owner1"))
                .isInstanceOf(ApiException.class)
                .extracting("status").isEqualTo(HttpStatus.BAD_REQUEST);
        // 시작일 > 종료일
        assertThatThrownBy(() -> settlementService.getStoreSettlement(20L, TO, FROM, "owner1"))
                .isInstanceOf(ApiException.class)
                .extracting("status").isEqualTo(HttpStatus.BAD_REQUEST);
        verify(paymentRepository, never()).findSettlementTargets(anyLong(), any(), any());
    }
}
