package com.sparta.springdeliverymini.service;

import com.sparta.springdeliverymini.TestcontainersConfiguration;
import com.sparta.springdeliverymini.dto.*;
import com.sparta.springdeliverymini.entity.*;
import com.sparta.springdeliverymini.exception.InvalidOrderStatusException;
import com.sparta.springdeliverymini.exception.InvalidPaymentStatusException;
import com.sparta.springdeliverymini.repository.*;
import com.sparta.springdeliverymini.scheduler.SettlementScheduler;
import com.sparta.springdeliverymini.service.SettlementIntegrationTest.MutableClock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;

// 결제 취소·환불과 차기 정산 반영을 실제 PostgreSQL로 검증 (docs/settlement-policy.md 9장)
// - 이미 만들어진 정산(PAID 포함)은 바뀌지 않고, 늦게 들어온 환불은 다음 정산에서 차감
// - 이월분은 중복 차감·누락 없이 정확히 한 번, 더 뒤 날짜의 정산에서만 차감 (날짜 역순 집계 포함)
// - 동시 환불·취소/수락 경합·같은 매장 동시 집계에서도 한 번만 반영
@SpringBootTest
@Import({TestcontainersConfiguration.class, SettlementIntegrationTest.MutableClockConfig.class})
class PaymentRefundIntegrationTest {

    private static final long PRICE = 10000; // 수수료 500, 정산액 9500 (수수료율 5%)
    private static final long NET = 9500;

    @Autowired private MutableClock clock;
    @Autowired private JdbcTemplate jdbc;

    @Autowired private StoreService storeService;
    @Autowired private MenuService menuService;
    @Autowired private OrderService orderService;
    @Autowired private PaymentService paymentService;
    @Autowired private SettlementService settlementService;
    @Autowired private SettlementAggregationService aggregationService;
    @Autowired private SettlementScheduler settlementScheduler;
    @Autowired private AdminSettlementService adminSettlementService;
    @Autowired private AdminPaymentService adminPaymentService;

    @Autowired private UserRepository userRepository;
    @Autowired private StoreRepository storeRepository;
    @Autowired private MenuRepository menuRepository;
    @Autowired private OrderRepository orderRepository;
    @Autowired private PaymentRepository paymentRepository;
    @Autowired private SettlementRepository settlementRepository;
    @Autowired private SettlementStatusHistoryRepository settlementStatusHistoryRepository;
    @Autowired private SettlementAggregationFailureRepository settlementAggregationFailureRepository;
    @Autowired private SettlementRefundRepository settlementRefundRepository;
    @Autowired private SettlementCarryoverRepository settlementCarryoverRepository;

    private StoreResponse store;
    private MenuResponse menu;

    @BeforeEach
    void setUp() {
        clock.set(LocalDateTime.of(2026, 10, 31, 12, 0));
        userRepository.save(new User("admin", "encoded-password", Role.ADMIN));
        userRepository.save(new User("owner1", "encoded-password", Role.OWNER));
        userRepository.save(new User("customer1", "encoded-password", Role.CUSTOMER));
        store = storeService.createStore(new StoreCreateRequest("치킨집"), "owner1");
        menu = menuService.createMenu(new MenuCreateRequest(store.id(), "치킨", PRICE, null), "owner1");
    }

    @AfterEach
    void tearDown() {
        // FK 순서대로 삭제: 차감 기록 → settlements → payments → orders → menus → stores → users
        // 정산을 참조하는 감사 이력·실패 기록(V11)을 먼저 삭제
        settlementStatusHistoryRepository.deleteAllInBatch();
        settlementAggregationFailureRepository.deleteAllInBatch();
        settlementCarryoverRepository.deleteAllInBatch();
        settlementRefundRepository.deleteAllInBatch();
        settlementRepository.deleteAllInBatch();
        paymentRepository.deleteAllInBatch();
        orderRepository.deleteAllInBatch();
        menuRepository.deleteAllInBatch();
        storeRepository.deleteAllInBatch();
        userRepository.deleteAllInBatch();
    }

    // ===== 도우미 =====

    // 결제만 완료된 주문 (PAYMENT_COMPLETED) → 결제 응답
    private PaymentResponse paidOrder(LocalDateTime paidAt) {
        clock.set(paidAt);
        OrderResponse order = orderService.createOrder(
                new OrderCreateRequest(List.of(new OrderItemRequest(menu.id(), 1)), "서울시"), "customer1");
        return paymentService.pay(new PaymentRequest(order.id(), "CARD"), "customer1");
    }

    // deliveredAt에 배달완료된 주문의 결제 id
    private Long delivered(LocalDateTime deliveredAt) {
        PaymentResponse payment = paidOrder(deliveredAt.minusHours(1));
        orderService.statusOrder(payment.orderId(), "owner1", OrderStatus.ORDER_ACCEPTED);
        clock.set(deliveredAt);
        orderService.statusOrder(payment.orderId(), "owner1", OrderStatus.DELIVERY_COMPLETED);
        return payment.id();
    }

    private void refund(Long paymentId, LocalDateTime refundedAt) {
        clock.set(refundedAt);
        adminPaymentService.refund(paymentId, "admin");
    }

    // date일 정산 집계 (date+1 01:10에 실행)
    private void aggregate(LocalDate date) {
        clock.set(date.plusDays(1).atTime(1, 10));
        aggregationService.aggregateDaily(date);
    }

    private Settlement settlement(LocalDate date) {
        return settlementRepository.findByStoreIdAndSettlementDate(store.id(), date).orElseThrow();
    }

    private static LocalDate nov(int day) {
        return LocalDate.of(2026, 11, day);
    }

    private static LocalDateTime nov(int day, int hour) {
        return LocalDateTime.of(2026, 11, day, hour, 0);
    }

    private Map<String, Object> row(Long settlementId) {
        return jdbc.queryForMap("SELECT * FROM settlements WHERE id = ?", settlementId);
    }

    private long count(String sql) {
        return jdbc.queryForObject(sql, Long.class);
    }

    // 회계 항등식 — 정산마다 "지급액 - 다음 이월액 = 판매 - 환불 - 이월받은 금액"이므로 전부 더하면
    //   Σ지급액 = Σ판매 정산액 - Σ환불 정산액 + 아직 차감 안 된 이월분 (아직 회수하지 못한 초과 지급분)
    // + 정산 행의 합계와 차감 기록의 합이 일치
    private void assertLedgerConsistent() {
        long payout = count("SELECT COALESCE(SUM(settlement_amount), 0) FROM settlements");
        long sales = count("SELECT COALESCE(SUM(sales_settlement_amount), 0) FROM settlements");
        long refunds = count("SELECT COALESCE(SUM(refund_settlement_amount), 0) FROM settlements");
        long unsettledCarry = count("SELECT COALESCE(SUM(s.carried_out_amount), 0) FROM settlements s "
                + "WHERE NOT EXISTS (SELECT 1 FROM settlement_carryovers c WHERE c.from_settlement_id = s.id)");
        assertThat(payout).isEqualTo(sales - refunds + unsettledCarry);

        assertThat(count("SELECT COUNT(*) FROM settlements s WHERE s.refund_settlement_amount <> "
                + "(SELECT COALESCE(SUM(r.settlement_amount), 0) FROM settlement_refunds r WHERE r.settlement_id = s.id)"))
                .as("환불 합계 = 연결된 환불 기록의 합").isZero();
        assertThat(count("SELECT COUNT(*) FROM settlements s WHERE s.refund_count <> "
                + "(SELECT COUNT(*) FROM settlement_refunds r WHERE r.settlement_id = s.id)"))
                .as("환불 건수 = 연결된 환불 기록 수").isZero();
        assertThat(count("SELECT COUNT(*) FROM settlements s WHERE s.carried_in_amount <> "
                + "(SELECT COALESCE(SUM(c.amount), 0) FROM settlement_carryovers c WHERE c.to_settlement_id = s.id)"))
                .as("이월받은 금액 = 연결된 이월 기록의 합").isZero();
        assertThat(count("SELECT COUNT(*) FROM settlement_carryovers c JOIN settlements f ON f.id = c.from_settlement_id "
                + "JOIN settlements t ON t.id = c.to_settlement_id WHERE f.settlement_date >= t.settlement_date"))
                .as("이월분은 더 뒤 날짜의 정산에서만 차감").isZero();
        assertThat(count("SELECT COUNT(*) FROM settlement_refunds r JOIN payments p ON p.id = r.payment_id "
                + "JOIN settlements s ON s.id = r.settlement_id WHERE p.refunded_at >= s.settlement_date + 1"))
                .as("환불은 환불일 이후 날짜의 정산에서만 차감").isZero();
    }

    // ===== 취소 (배달 전) =====

    @Test
    void 결제완료_주문을_고객이_취소하면_결제도_취소되고_정산_대상이_아니다() {
        PaymentResponse payment = paidOrder(nov(5, 11));

        clock.set(nov(5, 11).plusMinutes(5));
        orderService.cancelOrder(payment.orderId(), "customer1");

        Payment reloaded = paymentRepository.findById(payment.id()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(PaymentStatus.CANCELLED);
        assertThat(reloaded.getCancelledAt()).isEqualTo(nov(5, 11).plusMinutes(5));
        assertThat(orderRepository.findById(payment.orderId()).orElseThrow().getStatus())
                .isEqualTo(OrderStatus.ORDER_CANCELLED);

        aggregate(nov(5));
        assertThat(settlementRepository.count()).isZero();
    }

    // ===== 환불 반영 =====

    @Test
    void 배달_후_환불은_확정된_날짜를_바꾸지_않고_환불일_정산에서_차감한다() {
        Long paymentId = delivered(nov(2, 12));
        delivered(nov(2, 13));
        aggregate(nov(2));
        StoreSettlementResponse nov2Before = settlementService.getStoreSettlement(store.id(), nov(2), nov(2), "owner1");

        refund(paymentId, nov(5, 15));
        delivered(nov(5, 18));
        aggregate(nov(5));

        // 11/2 정산과 사장님 조회 결과는 그대로
        Settlement nov2 = settlement(nov(2));
        assertThat(nov2.getSettlementAmount()).isEqualTo(2 * NET);
        assertThat(nov2.getRefundCount()).isZero();
        clock.set(nov(8, 12));
        assertThat(settlementService.getStoreSettlement(store.id(), nov(2), nov(2), "owner1")).isEqualTo(nov2Before);

        // 11/5 정산에서 차감
        Settlement nov5 = settlement(nov(5));
        assertThat(nov5.getPaymentCount()).isEqualTo(1);
        assertThat(nov5.getRefundCount()).isEqualTo(1);
        assertThat(nov5.getRefundAmount()).isEqualTo(PRICE);
        assertThat(nov5.getRefundFee()).isEqualTo(500);
        assertThat(nov5.getSettlementAmount()).isZero(); // 9500 - 9500
        assertThat(nov5.getCarriedOutAmount()).isZero();

        // 사장님 조회: 환불은 환불일(11/5)에 보임
        StoreSettlementResponse nov5View = settlementService.getStoreSettlement(store.id(), nov(5), nov(5), "owner1");
        assertThat(nov5View.refundCount()).isEqualTo(1);
        assertThat(nov5View.netSettlementAmount()).isZero();
        assertThat(nov5View.refunds()).singleElement().satisfies(r -> assertThat(r.paymentId()).isEqualTo(paymentId));
        assertLedgerConsistent();
    }

    @Test
    void 배달_당일_환불이면_그날_정산의_순액은_0이다() {
        Long paymentId = delivered(nov(5, 12));
        refund(paymentId, nov(5, 15));

        aggregate(nov(5));

        Settlement nov5 = settlement(nov(5));
        assertThat(nov5.getPaymentCount()).isEqualTo(1);
        assertThat(nov5.getRefundCount()).isEqualTo(1);
        assertThat(nov5.getSettlementAmount()).isZero();
        assertThat(nov5.getCarriedOutAmount()).isZero();
        assertLedgerConsistent();
    }

    @Test
    void 환불일_정산이_이미_PAID여도_그_정산은_바뀌지_않고_늦게_커밋된_환불은_다음_정산에서_차감한다() {
        Long oldPayment = delivered(nov(1, 12));
        aggregate(nov(1));
        delivered(nov(5, 12));
        aggregate(nov(5));
        Long nov5Id = settlement(nov(5)).getId();
        clock.set(nov(6, 9));
        adminSettlementService.confirm(nov5Id, "admin");
        clock.set(nov(6, 10));
        adminSettlementService.pay(nov5Id, "admin");
        Map<String, Object> paidBefore = row(nov5Id);

        // 11/5 정산이 지급된 뒤에, 환불 시각이 11/5인 환불이 커밋됨 (긴 트랜잭션·시계 차이 등)
        refund(oldPayment, nov(5, 23));
        clock.set(nov(6, 1).plusMinutes(10));
        settlementScheduler.aggregateDailySettlements(); // 11/5는 이미 있으므로 건너뜀
        assertThat(row(nov5Id)).isEqualTo(paidBefore);
        assertThat(count("SELECT COUNT(*) FROM settlement_refunds")).isZero(); // 아직 미차감

        // 다음 날 집계: 판매가 없어도 미차감 환불이 있으므로 11/6 정산을 만들어 차감
        aggregate(nov(6));

        assertThat(row(nov5Id)).isEqualTo(paidBefore); // PAID 정산은 모든 컬럼(updated_at 포함) 그대로
        Settlement nov6 = settlement(nov(6));
        assertThat(nov6.getPaymentCount()).isZero();
        assertThat(nov6.getRefundCount()).isEqualTo(1);
        assertThat(nov6.getSettlementAmount()).isZero();
        assertThat(nov6.getCarriedOutAmount()).isEqualTo(NET); // 다음 정산으로 이월
        assertThat(settlementRefundRepository.findAllBySettlementId(nov6.getId()))
                .singleElement().satisfies(r -> assertThat(r.getPayment().getId()).isEqualTo(oldPayment));
        assertLedgerConsistent();
    }

    @Test
    void catch_up_기간을_넘긴_미차감_환불도_누락되지_않는다() {
        Long paymentId = delivered(nov(1, 12));
        aggregate(nov(1));
        refund(paymentId, nov(2, 10));

        // 스케줄러가 오래 멈춰 있다가 11/20에 재개 → catch-up은 11/13~11/19만 처리
        clock.set(nov(20, 1).plusMinutes(10));
        settlementScheduler.aggregateDailySettlements();

        assertThat(settlementRepository.findByStoreIdAndSettlementDate(store.id(), nov(2))).isEmpty();
        Settlement nov13 = settlement(nov(13)); // catch-up의 첫 날짜에서 차감
        assertThat(nov13.getRefundCount()).isEqualTo(1);
        assertThat(nov13.getCarriedOutAmount()).isEqualTo(NET);
        assertThat(count("SELECT COUNT(*) FROM settlement_refunds")).isEqualTo(1);
        assertLedgerConsistent();
    }

    // ===== 이월 =====

    // 11/1 판매 3건 / 11/2 판매 1건 + 11/1 결제 3건 환불 / 11/3 판매 1건 / 11/4 판매 5건
    private void carryoverScenario() {
        List<Long> nov1 = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            nov1.add(delivered(nov(1, 12 + i)));
        }
        delivered(nov(2, 12));
        for (Long paymentId : nov1) {
            refund(paymentId, nov(2, 15));
        }
        delivered(nov(3, 12));
        for (int i = 0; i < 5; i++) {
            delivered(nov(4, 12 + i));
        }
    }

    @Test
    void 날짜_순서대로_집계하면_이월분이_다음_정산으로_넘어가며_차감된다() {
        carryoverScenario();

        clock.set(nov(5, 1).plusMinutes(10));
        settlementScheduler.aggregateDailySettlements(); // 오래된 날짜부터

        assertThat(settlement(nov(1)).getSettlementAmount()).isEqualTo(3 * NET);
        Settlement nov2 = settlement(nov(2));
        assertThat(nov2.getRefundCount()).isEqualTo(3);
        assertThat(nov2.getSettlementAmount()).isZero();
        assertThat(nov2.getCarriedOutAmount()).isEqualTo(2 * NET);  // 9500 - 28500
        Settlement nov3 = settlement(nov(3));
        assertThat(nov3.getCarriedInAmount()).isEqualTo(2 * NET);
        assertThat(nov3.getSettlementAmount()).isZero();
        assertThat(nov3.getCarriedOutAmount()).isEqualTo(NET);      // 9500 - 19000
        Settlement nov4 = settlement(nov(4));
        assertThat(nov4.getCarriedInAmount()).isEqualTo(NET);
        assertThat(nov4.getSettlementAmount()).isEqualTo(4 * NET);  // 47500 - 9500
        assertThat(nov4.getCarriedOutAmount()).isZero();

        assertThat(count("SELECT COUNT(*) FROM settlement_refunds")).isEqualTo(3);
        assertThat(count("SELECT COUNT(*) FROM settlement_carryovers")).isEqualTo(2);
        assertLedgerConsistent();
    }

    @Test
    void 날짜_역순으로_집계해도_이월분은_앞_날짜로_거슬러_가지_않고_총_지급액은_같다() {
        carryoverScenario();

        // 11/3 → 11/2 → 11/4 → 11/1 순서로 집계 (집계 실패 후 재시도 등)
        aggregate(nov(3));
        aggregate(nov(2));
        aggregate(nov(4));
        aggregate(nov(1));

        // 11/3이 먼저 집계되면서 11/2에 환불된 3건을 가져감 (환불일 이후 날짜이므로 허용)
        Settlement nov3 = settlement(nov(3));
        assertThat(nov3.getRefundCount()).isEqualTo(3);
        assertThat(nov3.getCarriedOutAmount()).isEqualTo(2 * NET);
        // 11/2는 늦게 집계됐지만 11/3의 이월분을 가져가지 않음 (더 앞 날짜)
        Settlement nov2 = settlement(nov(2));
        assertThat(nov2.getRefundCount()).isZero();
        assertThat(nov2.getCarriedInAmount()).isZero();
        assertThat(nov2.getSettlementAmount()).isEqualTo(NET);
        // 11/4가 11/3의 이월분을 차감
        Settlement nov4 = settlement(nov(4));
        assertThat(nov4.getCarriedInAmount()).isEqualTo(2 * NET);
        assertThat(nov4.getSettlementAmount()).isEqualTo(3 * NET);

        // 총 지급액은 날짜 순서대로 집계했을 때와 같음 (3 + 0 + 0 + 4 = 7건분)
        assertThat(count("SELECT SUM(settlement_amount) FROM settlements")).isEqualTo(7 * NET);
        assertThat(count("SELECT COUNT(*) FROM settlement_refunds")).isEqualTo(3);
        assertLedgerConsistent();
    }

    @Test
    void 이월분만_남은_매장은_다음_판매나_환불이_있을_때_차감한다() {
        Long paymentId = delivered(nov(1, 12));
        aggregate(nov(1));
        refund(paymentId, nov(2, 10));
        aggregate(nov(2)); // 환불만 → 이월 9500
        aggregate(nov(3)); // 판매·환불 없음 → 정산 없음, 이월 대기

        assertThat(settlementRepository.findByStoreIdAndSettlementDate(store.id(), nov(3))).isEmpty();

        delivered(nov(4, 12));
        delivered(nov(4, 13));
        aggregate(nov(4));

        Settlement nov4 = settlement(nov(4));
        assertThat(nov4.getCarriedInAmount()).isEqualTo(NET);
        assertThat(nov4.getSettlementAmount()).isEqualTo(NET); // 19000 - 9500
        assertLedgerConsistent();
    }

    // ===== 동시성 =====

    @Test
    void 같은_결제를_동시에_환불해도_한_번만_성공한다() throws Exception {
        Long paymentId = delivered(nov(5, 12));
        clock.set(nov(6, 10));

        List<Throwable> failures = runConcurrently(5, () -> adminPaymentService.refund(paymentId, "admin"));

        assertThat(failures).hasSize(4).allSatisfy(e -> assertThat(e).isInstanceOf(InvalidPaymentStatusException.class));
        Payment reloaded = paymentRepository.findById(paymentId).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(PaymentStatus.REFUNDED);
        assertThat(reloaded.getRefundedAt()).isEqualTo(nov(6, 10));
    }

    @Test
    void 고객_취소와_사장님_수락이_동시에_들어오면_한쪽만_성공하고_주문과_결제_상태가_일치한다() throws Exception {
        for (int i = 0; i < 5; i++) {
            PaymentResponse payment = paidOrder(nov(5, 11));
            clock.set(nov(5, 11).plusMinutes(1));

            List<Callable<Object>> tasks = List.of(
                    () -> { orderService.cancelOrder(payment.orderId(), "customer1"); return null; },
                    () -> { orderService.statusOrder(payment.orderId(), "owner1", OrderStatus.ORDER_ACCEPTED); return null; });
            List<Throwable> failures = runAll(tasks);

            assertThat(failures).hasSize(1).allSatisfy(e -> assertThat(e).isInstanceOf(InvalidOrderStatusException.class));
            OrderStatus orderStatus = orderRepository.findById(payment.orderId()).orElseThrow().getStatus();
            PaymentStatus paymentStatus = paymentRepository.findById(payment.id()).orElseThrow().getStatus();
            if (orderStatus == OrderStatus.ORDER_CANCELLED) {
                assertThat(paymentStatus).isEqualTo(PaymentStatus.CANCELLED);
            } else {
                assertThat(orderStatus).isEqualTo(OrderStatus.ORDER_ACCEPTED);
                assertThat(paymentStatus).isEqualTo(PaymentStatus.COMPLETED);
            }
        }
    }

    @Test
    void 같은_매장의_서로_다른_날짜를_동시에_집계해도_환불과_이월은_한_번씩만_차감된다() throws Exception {
        carryoverScenario();
        clock.set(nov(5, 1).plusMinutes(10));

        // 11/2, 11/3, 11/4를 동시에 집계 → 매장 행 잠금으로 한 번에 하나씩
        List<Callable<Object>> tasks = List.of(
                () -> aggregationService.aggregateDaily(nov(2)),
                () -> aggregationService.aggregateDaily(nov(3)),
                () -> aggregationService.aggregateDaily(nov(4)));
        assertThat(runAll(tasks)).isEmpty();
        aggregationService.aggregateDaily(nov(1));

        assertThat(settlementRepository.count()).isEqualTo(4);
        assertThat(count("SELECT COUNT(*) FROM settlement_refunds")).isEqualTo(3);
        assertThat(count("SELECT COUNT(DISTINCT payment_id) FROM settlement_refunds")).isEqualTo(3);
        // 총 지급액은 실행 순서와 관계없이 7건분 (판매 10건 - 환불 3건)
        long unsettled = count("SELECT COALESCE(SUM(s.carried_out_amount), 0) FROM settlements s "
                + "WHERE NOT EXISTS (SELECT 1 FROM settlement_carryovers c WHERE c.from_settlement_id = s.id)");
        assertThat(count("SELECT SUM(settlement_amount) FROM settlements") - unsettled).isEqualTo(7 * NET);
        assertLedgerConsistent();
    }

    // ===== 동시 실행 도우미 =====

    private List<Throwable> runConcurrently(int threads, Callable<Object> task) throws Exception {
        List<Callable<Object>> tasks = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            tasks.add(task);
        }
        return runAll(tasks);
    }

    // 모든 작업을 동시에 시작하고, 실패한 작업의 예외 목록을 돌려줌
    private static List<Throwable> runAll(List<Callable<Object>> tasks) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(tasks.size());
        CountDownLatch ready = new CountDownLatch(tasks.size());
        CountDownLatch start = new CountDownLatch(1);
        List<Throwable> failures = new ArrayList<>();
        try {
            List<Future<Object>> futures = new ArrayList<>();
            for (Callable<Object> task : tasks) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    return task.call();
                }));
            }
            ready.await();
            start.countDown();
            for (Future<Object> future : futures) {
                try {
                    future.get(30, TimeUnit.SECONDS);
                } catch (ExecutionException e) {
                    failures.add(e.getCause());
                }
            }
        } finally {
            executor.shutdownNow();
        }
        return failures;
    }
}
