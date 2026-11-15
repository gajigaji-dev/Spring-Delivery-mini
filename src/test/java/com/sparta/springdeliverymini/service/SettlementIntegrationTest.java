package com.sparta.springdeliverymini.service;

import com.sparta.springdeliverymini.TestcontainersConfiguration;
import com.sparta.springdeliverymini.config.TimeConfig;
import com.sparta.springdeliverymini.dto.*;
import com.sparta.springdeliverymini.entity.OrderStatus;
import com.sparta.springdeliverymini.entity.Role;
import com.sparta.springdeliverymini.entity.User;
import com.sparta.springdeliverymini.exception.ApiException;
import com.sparta.springdeliverymini.repository.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpStatus;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// 주문 → 결제 → 수락 → 배달완료 → 정산 조회 흐름을 실제 PostgreSQL로 검증
// 배달완료 시각을 날짜 경계 앞뒤로 바꿔 가며 기록해 반열림 구간 [D 00:00, D+1 00:00)이 DB 쿼리에서도 지켜지는지 확인
@SpringBootTest
@Import({TestcontainersConfiguration.class, SettlementIntegrationTest.MutableClockConfig.class})
class SettlementIntegrationTest {

    private static final LocalDate NOV_5 = LocalDate.of(2026, 11, 5);

    // 테스트 중에 현재 시각을 바꿀 수 있는 Clock — 배달완료 시각을 원하는 값으로 기록하기 위해 사용
    static class MutableClock extends Clock {
        private Instant instant = Instant.now();

        void set(LocalDateTime now) {
            instant = now.atZone(TimeConfig.ZONE).toInstant();
        }

        @Override public ZoneId getZone() { return TimeConfig.ZONE; }
        @Override public Clock withZone(ZoneId zone) { throw new UnsupportedOperationException(); }
        @Override public Instant instant() { return instant; }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class MutableClockConfig {
        // TimeConfig의 clock 대신 주입되도록 @Primary (Auditing·결제 시각·배달완료 시각 모두 이 Clock 사용)
        @Bean
        @Primary
        MutableClock mutableClock() {
            return new MutableClock();
        }
    }

    @Autowired private MutableClock clock;

    @Autowired private StoreService storeService;
    @Autowired private MenuService menuService;
    @Autowired private OrderService orderService;
    @Autowired private PaymentService paymentService;
    @Autowired private SettlementService settlementService;

    @Autowired private UserRepository userRepository;
    @Autowired private StoreRepository storeRepository;
    @Autowired private MenuRepository menuRepository;
    @Autowired private OrderRepository orderRepository;
    @Autowired private PaymentRepository paymentRepository;

    private StoreResponse chickenStore;
    private StoreResponse otherOwnerStore;

    @BeforeEach
    void setUp() {
        clock.set(LocalDateTime.of(2026, 11, 4, 12, 0));
        userRepository.save(new User("owner1", "encoded-password", Role.OWNER));
        userRepository.save(new User("owner2", "encoded-password", Role.OWNER));
        userRepository.save(new User("customer1", "encoded-password", Role.CUSTOMER));

        chickenStore = storeService.createStore(new StoreCreateRequest("치킨집"), "owner1");
        otherOwnerStore = storeService.createStore(new StoreCreateRequest("분식집"), "owner2");
    }

    @AfterEach
    void tearDown() {
        // FK 순서대로 삭제: payments → orders(order_items는 CASCADE) → menus → stores → users
        paymentRepository.deleteAllInBatch();
        orderRepository.deleteAllInBatch();
        menuRepository.deleteAllInBatch();
        storeRepository.deleteAllInBatch();
        userRepository.deleteAllInBatch();
    }

    // 주문 → 결제 (수수료 5%)
    private OrderResponse paidOrder(StoreResponse store, String ownerName, long price) {
        MenuResponse menu = menuService.createMenu(new MenuCreateRequest(store.id(), "메뉴", price, null), ownerName);
        OrderResponse order = orderService.createOrder(
                new OrderCreateRequest(List.of(new OrderItemRequest(menu.id(), 1)), "서울시"), "customer1");
        paymentService.pay(new PaymentRequest(order.id(), "CARD"), "customer1");
        return order;
    }

    // 주문 → 결제 → 수락 → deliveredAt 시각에 배달완료
    private OrderResponse deliveredOrder(StoreResponse store, String ownerName, long price, LocalDateTime deliveredAt) {
        OrderResponse order = paidOrder(store, ownerName, price);
        orderService.statusOrder(order.id(), ownerName, OrderStatus.ORDER_ACCEPTED);
        clock.set(deliveredAt);
        orderService.statusOrder(order.id(), ownerName, OrderStatus.DELIVERY_COMPLETED);
        return order;
    }

    @Test
    void 배달완료_시각이_조회일_0시_이상_다음날_0시_미만인_결제만_정산된다() {
        // 11/5 경계 앞뒤
        deliveredOrder(chickenStore, "owner1", 10000, LocalDateTime.of(2026, 11, 4, 23, 59, 59, 999_999_000)); // 전날
        OrderResponse startOfDay = deliveredOrder(chickenStore, "owner1", 18009, LocalDateTime.of(2026, 11, 5, 0, 0));
        OrderResponse endOfDay = deliveredOrder(chickenStore, "owner1", 20000, LocalDateTime.of(2026, 11, 5, 23, 59, 59, 999_999_000));
        deliveredOrder(chickenStore, "owner1", 30000, LocalDateTime.of(2026, 11, 6, 0, 0)); // 다음 날
        // 정산 대상이 아닌 주문: 결제만 됨(배달 전), 다른 점주의 매장
        clock.set(LocalDateTime.of(2026, 11, 5, 12, 0));
        paidOrder(chickenStore, "owner1", 40000);
        deliveredOrder(otherOwnerStore, "owner2", 50000, LocalDateTime.of(2026, 11, 5, 12, 0));

        clock.set(LocalDateTime.of(2026, 11, 6, 9, 0)); // 11/5 정산 확정 이후
        StoreSettlementResponse response = settlementService.getStoreSettlement(chickenStore.id(), NOV_5, NOV_5, "owner1");

        assertThat(response.items()).extracting(SettlementItemResponse::orderId)
                .containsExactly(startOfDay.id(), endOfDay.id()); // 배달완료 시각 순
        assertThat(response.paymentCount()).isEqualTo(2);
        assertThat(response.totalAmount()).isEqualTo(38009L);
        assertThat(response.totalFee()).isEqualTo(900L + 1000L); // 18,009 × 5% = 900.45 → 900, 20,000 × 5% = 1,000
        assertThat(response.settlementAmount()).isEqualTo(17109L + 19000L);
        assertThat(response.items().get(1).deliveredAt())
                .isEqualTo(LocalDateTime.of(2026, 11, 5, 23, 59, 59, 999_999_000)); // DB에서도 마이크로초까지 유지
    }

    @Test
    void 배달완료하면_주문_응답에_배달완료_시각이_담긴다() {
        OrderResponse order = deliveredOrder(chickenStore, "owner1", 10000, LocalDateTime.of(2026, 11, 5, 18, 30));

        OrderResponse reloaded = orderService.getStoreOrders(chickenStore.id(), "owner1", PageQuery.of(0, 20)).stream()
                .filter(o -> o.id().equals(order.id())).findFirst().orElseThrow();
        assertThat(reloaded.deliveredAt()).isEqualTo(LocalDateTime.of(2026, 11, 5, 18, 30));
    }

    @Test
    void 확정_전인_날짜는_조회할_수_없다() {
        clock.set(LocalDateTime.of(2026, 11, 6, 0, 30)); // 11/5는 01:00에 확정

        assertThatThrownBy(() -> settlementService.getStoreSettlement(chickenStore.id(), NOV_5, NOV_5, "owner1"))
                .isInstanceOf(ApiException.class)
                .extracting("status").isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void 다른_점주의_매장_정산은_403() {
        clock.set(LocalDateTime.of(2026, 11, 6, 9, 0));

        assertThatThrownBy(() -> settlementService.getStoreSettlement(otherOwnerStore.id(), NOV_5, NOV_5, "owner1"))
                .isInstanceOf(ApiException.class)
                .extracting("status").isEqualTo(HttpStatus.FORBIDDEN);
    }
}
