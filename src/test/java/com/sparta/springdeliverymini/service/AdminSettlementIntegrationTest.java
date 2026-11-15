package com.sparta.springdeliverymini.service;

import com.sparta.springdeliverymini.TestcontainersConfiguration;
import com.sparta.springdeliverymini.dto.*;
import com.sparta.springdeliverymini.entity.*;
import com.sparta.springdeliverymini.exception.ApiException;
import com.sparta.springdeliverymini.exception.InvalidSettlementStatusException;
import com.sparta.springdeliverymini.repository.*;
import com.sparta.springdeliverymini.scheduler.SettlementScheduler;
import com.sparta.springdeliverymini.service.SettlementIntegrationTest.MutableClock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// 스케줄러 집계(PENDING) → 관리자 확정(CONFIRMED) → 지급(PAID) 흐름을 실제 PostgreSQL로 검증
// - 각 단계의 상태·시각이 DB에 기록되는지
// - 잘못된 전이·중복 지급이 막히는지 (동시 지급 포함)
// - 관리자가 아니면 막히는지
// SettlementIntegrationTest와 같은 설정(@Import)을 써서 Spring 컨텍스트·DB 컨테이너를 함께 사용
@SpringBootTest
@Import({TestcontainersConfiguration.class, SettlementIntegrationTest.MutableClockConfig.class})
class AdminSettlementIntegrationTest {

    private static final LocalDate NOV_5 = LocalDate.of(2026, 11, 5);
    private static final LocalDateTime AGGREGATED_AT = LocalDateTime.of(2026, 11, 6, 1, 10);
    private static final LocalDateTime CONFIRMED_AT = LocalDateTime.of(2026, 11, 6, 9, 0);
    private static final LocalDateTime PAID_AT = LocalDateTime.of(2026, 11, 7, 10, 0);

    @Autowired private MutableClock clock;

    @Autowired private StoreService storeService;
    @Autowired private MenuService menuService;
    @Autowired private OrderService orderService;
    @Autowired private PaymentService paymentService;
    @Autowired private SettlementService settlementService;
    @Autowired private SettlementScheduler settlementScheduler;
    @Autowired private AdminSettlementService adminSettlementService;

    @Autowired private UserRepository userRepository;
    @Autowired private StoreRepository storeRepository;
    @Autowired private MenuRepository menuRepository;
    @Autowired private OrderRepository orderRepository;
    @Autowired private PaymentRepository paymentRepository;
    @Autowired private SettlementRepository settlementRepository;
    @Autowired private SettlementStatusHistoryRepository settlementStatusHistoryRepository;
    @Autowired private SettlementAggregationFailureRepository settlementAggregationFailureRepository;

    private StoreResponse chickenStore;

    @BeforeEach
    void setUp() {
        clock.set(LocalDateTime.of(2026, 11, 4, 12, 0));
        userRepository.save(new User("admin", "encoded-password", Role.ADMIN));
        userRepository.save(new User("owner1", "encoded-password", Role.OWNER));
        userRepository.save(new User("customer1", "encoded-password", Role.CUSTOMER));
        chickenStore = storeService.createStore(new StoreCreateRequest("치킨집"), "owner1");
    }

    @AfterEach
    void tearDown() {
        // FK 순서대로 삭제: settlements → payments → orders(order_items는 CASCADE) → menus → stores → users
        // 정산을 참조하는 감사 이력·실패 기록(V11)을 먼저 삭제
        settlementStatusHistoryRepository.deleteAllInBatch();
        settlementAggregationFailureRepository.deleteAllInBatch();
        settlementRepository.deleteAllInBatch();
        paymentRepository.deleteAllInBatch();
        orderRepository.deleteAllInBatch();
        menuRepository.deleteAllInBatch();
        storeRepository.deleteAllInBatch();
        userRepository.deleteAllInBatch();
    }

    // 11/5에 배달완료된 주문 2건 → 11/6 01:10 스케줄러 집계 → PENDING 정산 id
    private Long aggregatedSettlementId() {
        deliveredOrder(18009, LocalDateTime.of(2026, 11, 5, 12, 0));
        deliveredOrder(20000, LocalDateTime.of(2026, 11, 5, 19, 0));
        clock.set(AGGREGATED_AT);
        settlementScheduler.aggregateDailySettlements();
        return settlementRepository.findByStoreIdAndSettlementDate(chickenStore.id(), NOV_5).orElseThrow().getId();
    }

    private void deliveredOrder(long price, LocalDateTime deliveredAt) {
        clock.set(deliveredAt.minusHours(1));
        MenuResponse menu = menuService.createMenu(new MenuCreateRequest(chickenStore.id(), "메뉴", price, null), "owner1");
        OrderResponse order = orderService.createOrder(
                new OrderCreateRequest(List.of(new OrderItemRequest(menu.id(), 1)), "서울시"), "customer1");
        paymentService.pay(new PaymentRequest(order.id(), "CARD"), "customer1");
        orderService.statusOrder(order.id(), "owner1", OrderStatus.ORDER_ACCEPTED);
        clock.set(deliveredAt);
        orderService.statusOrder(order.id(), "owner1", OrderStatus.DELIVERY_COMPLETED);
    }

    private Settlement reload(Long id) {
        return settlementRepository.findById(id).orElseThrow();
    }

    @Test
    void 집계된_정산을_관리자가_확정하고_지급하면_상태와_시각이_DB에_기록된다() {
        Long id = aggregatedSettlementId();

        // 1. 집계 직후: PENDING
        assertThat(adminSettlementService.getSettlements(NOV_5, SettlementStatus.PENDING, "admin"))
                .singleElement()
                .satisfies(s -> {
                    assertThat(s.id()).isEqualTo(id);
                    assertThat(s.storeName()).isEqualTo("치킨집");
                    assertThat(s.totalAmount()).isEqualTo(38009L);
                    assertThat(s.settlementAmount()).isEqualTo(36109L);
                    assertThat(s.aggregatedAt()).isEqualTo(AGGREGATED_AT);
                    assertThat(s.confirmedAt()).isNull();
                });

        // 2. 확정
        clock.set(CONFIRMED_AT);
        SettlementResponse confirmed = adminSettlementService.confirm(id, "admin");
        assertThat(confirmed.status()).isEqualTo(SettlementStatus.CONFIRMED);
        assertThat(reload(id).getStatus()).isEqualTo(SettlementStatus.CONFIRMED);
        assertThat(reload(id).getConfirmedAt()).isEqualTo(CONFIRMED_AT);
        assertThat(adminSettlementService.getSettlements(NOV_5, SettlementStatus.PENDING, "admin")).isEmpty();

        // 3. 지급
        clock.set(PAID_AT);
        SettlementResponse paid = adminSettlementService.pay(id, "admin");
        assertThat(paid.status()).isEqualTo(SettlementStatus.PAID);
        assertThat(paid.paidAt()).isEqualTo(PAID_AT);

        Settlement reloaded = reload(id);
        assertThat(reloaded.getStatus()).isEqualTo(SettlementStatus.PAID);
        assertThat(reloaded.getConfirmedAt()).isEqualTo(CONFIRMED_AT);
        assertThat(reloaded.getPaidAt()).isEqualTo(PAID_AT);
        assertThat(reloaded.getTotalAmount()).isEqualTo(38009L); // 금액은 집계 때 그대로
    }

    @Test
    void 확정_전_지급과_중복_지급은_막히고_DB_값은_바뀌지_않는다() {
        Long id = aggregatedSettlementId();

        // 확정 전 지급
        clock.set(PAID_AT);
        assertThatThrownBy(() -> adminSettlementService.pay(id, "admin"))
                .isInstanceOf(InvalidSettlementStatusException.class);
        assertThat(reload(id).getStatus()).isEqualTo(SettlementStatus.PENDING);
        assertThat(reload(id).getPaidAt()).isNull();

        clock.set(CONFIRMED_AT);
        adminSettlementService.confirm(id, "admin");
        // 중복 확정
        assertThatThrownBy(() -> adminSettlementService.confirm(id, "admin"))
                .isInstanceOf(InvalidSettlementStatusException.class);

        clock.set(PAID_AT);
        adminSettlementService.pay(id, "admin");
        // 중복 지급 — 지급 시각은 처음 값 유지
        clock.set(PAID_AT.plusDays(1));
        assertThatThrownBy(() -> adminSettlementService.pay(id, "admin"))
                .isInstanceOf(InvalidSettlementStatusException.class);
        assertThat(reload(id).getPaidAt()).isEqualTo(PAID_AT);
    }

    @Test
    void 같은_정산을_동시에_지급해도_한_번만_성공한다() throws Exception {
        Long id = aggregatedSettlementId();
        clock.set(CONFIRMED_AT);
        adminSettlementService.confirm(id, "admin");
        clock.set(PAID_AT);

        int threads = 5;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        int succeeded = 0;
        int rejected = 0;
        try {
            List<Future<SettlementResponse>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(executor.submit(() -> {
                    start.await();
                    return adminSettlementService.pay(id, "admin");
                }));
            }
            start.countDown();

            for (Future<SettlementResponse> future : futures) {
                try {
                    future.get(30, TimeUnit.SECONDS);
                    succeeded++;
                } catch (ExecutionException e) {
                    // 행 잠금으로 앞 요청이 커밋될 때까지 기다린 뒤 PAID 상태를 읽고 거부됨
                    assertThat(e.getCause()).isInstanceOf(InvalidSettlementStatusException.class);
                    rejected++;
                }
            }
        } finally {
            executor.shutdownNow();
        }

        assertThat(succeeded).isEqualTo(1);
        assertThat(rejected).isEqualTo(threads - 1);
        assertThat(reload(id).getStatus()).isEqualTo(SettlementStatus.PAID);
        assertThat(reload(id).getPaidAt()).isEqualTo(PAID_AT);
    }

    @Test
    void 관리자가_아니면_확정_지급할_수_없다() {
        Long id = aggregatedSettlementId();

        assertThatThrownBy(() -> adminSettlementService.confirm(id, "owner1"))
                .isInstanceOf(ApiException.class).extracting("status").isEqualTo(HttpStatus.FORBIDDEN);
        assertThatThrownBy(() -> adminSettlementService.pay(id, "customer1"))
                .isInstanceOf(ApiException.class).extracting("status").isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(reload(id).getStatus()).isEqualTo(SettlementStatus.PENDING);
    }

    @Test
    void 없는_정산은_404() {
        assertThatThrownBy(() -> adminSettlementService.confirm(Long.MAX_VALUE, "admin"))
                .isInstanceOf(ApiException.class).extracting("status").isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void 정산_상태가_바뀌어도_사장님_정산_조회_결과는_같다() {
        Long id = aggregatedSettlementId();
        StoreSettlementResponse before = settlementService.getStoreSettlement(chickenStore.id(), NOV_5, NOV_5, "owner1");

        clock.set(CONFIRMED_AT);
        adminSettlementService.confirm(id, "admin");
        clock.set(PAID_AT);
        adminSettlementService.pay(id, "admin");

        StoreSettlementResponse after = settlementService.getStoreSettlement(chickenStore.id(), NOV_5, NOV_5, "owner1");
        assertThat(after).isEqualTo(before);
    }
}
