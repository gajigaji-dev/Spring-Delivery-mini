package com.sparta.springdeliverymini.service;

import com.sparta.springdeliverymini.TestcontainersConfiguration;
import com.sparta.springdeliverymini.dto.*;
import com.sparta.springdeliverymini.entity.*;
import com.sparta.springdeliverymini.repository.*;
import com.sparta.springdeliverymini.scheduler.SettlementScheduler;
import com.sparta.springdeliverymini.service.SettlementConfirmService.DailyResult;
import com.sparta.springdeliverymini.service.SettlementIntegrationTest.MutableClock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.config.CronTask;
import org.springframework.scheduling.config.ScheduledTaskHolder;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// 배달완료 → 스케줄러 실행 → 일 정산 확정 흐름을 실제 PostgreSQL로 검증
// - 확정 금액이 정산 조회 API 결과와 같은지
// - 여러 번·동시에 실행해도 매장·날짜당 1건만 저장되는지 (DB UNIQUE 포함)
// - 스케줄러가 cron으로 등록되는지
// SettlementIntegrationTest와 같은 설정(@Import)을 써서 Spring 컨텍스트·DB 컨테이너를 함께 사용
@SpringBootTest
@Import({TestcontainersConfiguration.class, SettlementIntegrationTest.MutableClockConfig.class})
class SettlementConfirmIntegrationTest {

    private static final LocalDate NOV_4 = LocalDate.of(2026, 11, 4);
    private static final LocalDate NOV_5 = LocalDate.of(2026, 11, 5);

    @Autowired private MutableClock clock;

    @Autowired private StoreService storeService;
    @Autowired private MenuService menuService;
    @Autowired private OrderService orderService;
    @Autowired private PaymentService paymentService;
    @Autowired private SettlementService settlementService;
    @Autowired private SettlementConfirmService settlementConfirmService;
    @Autowired private SettlementScheduler settlementScheduler;
    @Autowired private ScheduledTaskHolder scheduledTaskHolder;

    @Autowired private UserRepository userRepository;
    @Autowired private StoreRepository storeRepository;
    @Autowired private MenuRepository menuRepository;
    @Autowired private OrderRepository orderRepository;
    @Autowired private PaymentRepository paymentRepository;
    @Autowired private SettlementRepository settlementRepository;
    @Autowired private JdbcTemplate jdbc;

    private StoreResponse chickenStore;
    private StoreResponse snackStore;
    private StoreResponse emptyStore;

    @BeforeEach
    void setUp() {
        clock.set(LocalDateTime.of(2026, 11, 4, 12, 0));
        userRepository.save(new User("owner1", "encoded-password", Role.OWNER));
        userRepository.save(new User("owner2", "encoded-password", Role.OWNER));
        userRepository.save(new User("customer1", "encoded-password", Role.CUSTOMER));

        chickenStore = storeService.createStore(new StoreCreateRequest("치킨집"), "owner1");
        snackStore = storeService.createStore(new StoreCreateRequest("분식집"), "owner2");
        emptyStore = storeService.createStore(new StoreCreateRequest("빈 매장"), "owner1"); // 배달완료 없음
    }

    @AfterEach
    void tearDown() {
        // FK 순서대로 삭제: settlements → payments → orders(order_items는 CASCADE) → menus → stores → users
        settlementRepository.deleteAllInBatch();
        paymentRepository.deleteAllInBatch();
        orderRepository.deleteAllInBatch();
        menuRepository.deleteAllInBatch();
        storeRepository.deleteAllInBatch();
        userRepository.deleteAllInBatch();
    }

    // 주문 → 결제(수수료 5%) → 수락 → deliveredAt 시각에 배달완료
    private void deliveredOrder(StoreResponse store, String ownerName, long price, LocalDateTime deliveredAt) {
        clock.set(deliveredAt.minusHours(1));
        MenuResponse menu = menuService.createMenu(new MenuCreateRequest(store.id(), "메뉴", price, null), ownerName);
        OrderResponse order = orderService.createOrder(
                new OrderCreateRequest(List.of(new OrderItemRequest(menu.id(), 1)), "서울시"), "customer1");
        paymentService.pay(new PaymentRequest(order.id(), "CARD"), "customer1");
        orderService.statusOrder(order.id(), ownerName, OrderStatus.ORDER_ACCEPTED);
        clock.set(deliveredAt);
        orderService.statusOrder(order.id(), ownerName, OrderStatus.DELIVERY_COMPLETED);
    }

    private Settlement settlementOf(StoreResponse store, LocalDate date) {
        return settlementRepository.findByStoreIdAndSettlementDate(store.id(), date).orElseThrow();
    }

    @Test
    void 스케줄러를_실행하면_매장별로_하루치가_확정되고_금액은_정산_조회와_같다() {
        deliveredOrder(chickenStore, "owner1", 18009, LocalDateTime.of(2026, 11, 5, 0, 0));
        deliveredOrder(chickenStore, "owner1", 20000, LocalDateTime.of(2026, 11, 5, 23, 59, 59, 999_999_000));
        deliveredOrder(chickenStore, "owner1", 30000, LocalDateTime.of(2026, 11, 6, 0, 0)); // 11/6분 → 아직 확정 안 됨
        deliveredOrder(snackStore, "owner2", 7000, LocalDateTime.of(2026, 11, 4, 13, 0));

        LocalDateTime runAt = LocalDateTime.of(2026, 11, 6, 1, 10);
        clock.set(runAt);
        settlementScheduler.confirmDailySettlements();

        // 치킨집 11/5: 2건
        Settlement chicken = settlementOf(chickenStore, NOV_5);
        StoreSettlementResponse queried = settlementService.getStoreSettlement(chickenStore.id(), NOV_5, NOV_5, "owner1");
        assertThat(chicken.getPaymentCount()).isEqualTo(2).isEqualTo(queried.paymentCount());
        assertThat(chicken.getTotalAmount()).isEqualTo(38009L).isEqualTo(queried.totalAmount());
        assertThat(chicken.getTotalFee()).isEqualTo(1900L).isEqualTo(queried.totalFee());
        assertThat(chicken.getSettlementAmount()).isEqualTo(36109L).isEqualTo(queried.settlementAmount());
        assertThat(chicken.getStatus()).isEqualTo(SettlementStatus.CONFIRMED);
        assertThat(chicken.getConfirmedAt()).isEqualTo(runAt);

        // 분식집 11/4: 지난 날짜도 catch-up으로 확정
        Settlement snack = settlementOf(snackStore, NOV_4);
        assertThat(snack.getTotalAmount()).isEqualTo(7000L);
        assertThat(snack.getSettlementAmount()).isEqualTo(6650L);

        // 정산 대상이 없는 매장·날짜는 기록을 만들지 않음, 11/6은 확정 전이라 없음
        assertThat(settlementRepository.findAll()).hasSize(2);
        assertThat(settlementRepository.findByStoreIdAndSettlementDate(emptyStore.id(), NOV_5)).isEmpty();
        assertThat(settlementRepository.findAllBySettlementDate(LocalDate.of(2026, 11, 6))).isEmpty();
    }

    @Test
    void 여러_번_실행해도_매장_날짜당_한_건만_확정되고_금액은_바뀌지_않는다() {
        deliveredOrder(chickenStore, "owner1", 10000, LocalDateTime.of(2026, 11, 5, 12, 0));
        clock.set(LocalDateTime.of(2026, 11, 6, 1, 10));
        settlementScheduler.confirmDailySettlements();
        Settlement first = settlementOf(chickenStore, NOV_5);

        // 다음 날 다시 실행 (catch-up 범위에 11/5 포함)
        clock.set(LocalDateTime.of(2026, 11, 7, 1, 10));
        settlementScheduler.confirmDailySettlements();
        DailyResult again = settlementConfirmService.confirmDaily(NOV_5);

        assertThat(again).isEqualTo(new DailyResult(NOV_5, 0, 1, 0));
        assertThat(settlementRepository.findAll()).hasSize(1);
        Settlement after = settlementOf(chickenStore, NOV_5);
        assertThat(after.getId()).isEqualTo(first.getId());
        assertThat(after.getTotalAmount()).isEqualTo(first.getTotalAmount());
        assertThat(after.getConfirmedAt()).isEqualTo(first.getConfirmedAt());
    }

    @Test
    void 동시에_확정해도_매장_날짜당_한_건만_저장된다() throws Exception {
        deliveredOrder(chickenStore, "owner1", 10000, LocalDateTime.of(2026, 11, 5, 12, 0));
        deliveredOrder(snackStore, "owner2", 20000, LocalDateTime.of(2026, 11, 5, 13, 0));
        clock.set(LocalDateTime.of(2026, 11, 6, 1, 10));

        int threads = 4;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<DailyResult>> futures = new java.util.ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(executor.submit(() -> {
                    start.await();
                    return settlementConfirmService.confirmDaily(NOV_5);
                }));
            }
            start.countDown();

            int confirmed = 0;
            int skipped = 0;
            for (Future<DailyResult> future : futures) {
                DailyResult result = future.get(30, TimeUnit.SECONDS);
                confirmed += result.confirmed();
                skipped += result.skipped();
                assertThat(result.failed()).isZero();
            }

            // 매장 2곳 × 실행 4번 → 확정 2번, 나머지 6번은 중복으로 건너뜀
            assertThat(confirmed).isEqualTo(2);
            assertThat(skipped).isEqualTo(6);
        } finally {
            executor.shutdownNow();
        }
        assertThat(settlementRepository.findAllBySettlementDate(NOV_5)).hasSize(2);
    }

    @Test
    void 같은_매장_날짜를_직접_두_번_저장하면_DB가_거부한다() {
        Store store = storeRepository.findById(chickenStore.id()).orElseThrow();
        LocalDateTime now = LocalDateTime.of(2026, 11, 6, 9, 0);
        SettlementTotals zero = new SettlementTotals(0, 0, 0, 0);
        settlementRepository.saveAndFlush(Settlement.confirm(store, NOV_5, zero, now));

        assertThatThrownBy(() -> settlementRepository.saveAndFlush(Settlement.confirm(store, NOV_5, zero, now)))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("uk_settlements_store_date");
    }

    @Test
    void 금액이_맞지_않거나_지급_상태와_지급_시각이_어긋나면_DB가_거부한다() {
        Store store = storeRepository.findById(chickenStore.id()).orElseThrow();
        Settlement saved = settlementRepository.saveAndFlush(Settlement.confirm(
                store, NOV_5, new SettlementTotals(1, 10000, 500, 9500), LocalDateTime.of(2026, 11, 6, 9, 0)));

        assertThatThrownBy(() -> jdbc.update("UPDATE settlements SET total_fee = 600 WHERE id = ?", saved.getId()))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("settlements_amount_check");
        assertThatThrownBy(() -> jdbc.update("UPDATE settlements SET status = 'PAID' WHERE id = ?", saved.getId()))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("settlements_paid_at_check");
    }

    @Test
    void 지급완료_상태가_DB에_저장된다() {
        Store store = storeRepository.findById(chickenStore.id()).orElseThrow();
        Settlement settlement = settlementRepository.saveAndFlush(Settlement.confirm(
                store, NOV_5, new SettlementTotals(1, 10000, 500, 9500), LocalDateTime.of(2026, 11, 6, 9, 0)));

        settlement.markPaid(LocalDateTime.of(2026, 11, 7, 10, 0));
        settlementRepository.saveAndFlush(settlement);

        Settlement reloaded = settlementRepository.findById(settlement.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(SettlementStatus.PAID);
        assertThat(reloaded.getPaidAt()).isEqualTo(LocalDateTime.of(2026, 11, 7, 10, 0));
    }

    @Test
    void 스케줄러가_매일_01시_10분_cron으로_등록된다() {
        assertThat(scheduledTaskHolder.getScheduledTasks())
                .map(scheduled -> scheduled.getTask())
                .filteredOn(task -> task instanceof CronTask)
                .map(task -> (CronTask) task)
                .anySatisfy(task -> {
                    assertThat(task.getExpression()).isEqualTo("0 10 1 * * *");
                    assertThat(task.toString()).contains("SettlementScheduler.confirmDailySettlements");
                });
    }
}
