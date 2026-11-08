package com.sparta.springdeliverymini.service;

import com.sparta.springdeliverymini.TestcontainersConfiguration;
import com.sparta.springdeliverymini.dto.*;
import com.sparta.springdeliverymini.entity.PaymentFee;
import com.sparta.springdeliverymini.entity.Role;
import com.sparta.springdeliverymini.entity.User;
import com.sparta.springdeliverymini.repository.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;

// 매장 수수료율 변경과 결제가 동시에 일어날 때 결제에 저장되는 수수료율이 일관적인지 실제 PostgreSQL로 검증
// - 결제는 매장 행을 잠그지 않고, 매장을 읽은 시점에 커밋된 수수료율 하나로 fee_rate·fee_amount·settlement_amount를 함께 계산
// - 커밋되지 않은 변경은 결제에 보이지 않고, 결제가 변경 트랜잭션을 기다리지도 않음
//
// @Transactional을 붙이지 않음 — 스레드마다 별도 트랜잭션으로 실제 커밋해야 동시성을 볼 수 있음 (@AfterEach에서 정리)
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class StoreFeeRateConcurrencyIntegrationTest {

    // 수수료율마다 수수료가 달라지는 금액 — 5% → 900, 3% → 540, 10% → 1801 (900.45, 540.27, 1800.9 반올림)
    private static final long PRICE = 18009;

    @Autowired private JdbcTemplate jdbc;
    @Autowired private TransactionTemplate transactionTemplate;

    @Autowired private StoreService storeService;
    @Autowired private MenuService menuService;
    @Autowired private OrderService orderService;
    @Autowired private PaymentService paymentService;
    @Autowired private AdminStoreService adminStoreService;

    @Autowired private UserRepository userRepository;
    @Autowired private StoreRepository storeRepository;
    @Autowired private MenuRepository menuRepository;
    @Autowired private OrderRepository orderRepository;
    @Autowired private PaymentRepository paymentRepository;

    private StoreResponse store;
    private MenuResponse menu;

    @BeforeEach
    void setUp() {
        userRepository.save(new User("admin", "encoded-password", Role.ADMIN));
        userRepository.save(new User("owner1", "encoded-password", Role.OWNER));
        userRepository.save(new User("customer1", "encoded-password", Role.CUSTOMER));
        store = storeService.createStore(new StoreCreateRequest("치킨집"), "owner1");
        menu = menuService.createMenu(new MenuCreateRequest(store.id(), "치킨", PRICE, null), "owner1");
    }

    @AfterEach
    void tearDown() {
        paymentRepository.deleteAllInBatch();
        orderRepository.deleteAllInBatch();
        menuRepository.deleteAllInBatch();
        storeRepository.deleteAllInBatch();
        userRepository.deleteAllInBatch();
    }

    private Long newOrder() {
        return orderService.createOrder(
                new OrderCreateRequest(List.of(new OrderItemRequest(menu.id(), 1)), "서울시"), "customer1").id();
    }

    private PaymentResponse pay(Long orderId) {
        return paymentService.pay(new PaymentRequest(orderId, "CARD"), "customer1");
    }

    private Map<String, Object> paymentRow(Long paymentId) {
        return jdbc.queryForMap("SELECT amount, fee_rate, fee_amount, settlement_amount FROM payments WHERE id = ?",
                paymentId);
    }

    // 저장된 행이 "자기 fee_rate 하나로 계산된 값"인지 — 수수료율과 금액이 서로 다른 시점의 값이 섞이지 않았는지
    private static void assertSelfConsistent(Map<String, Object> row) {
        long amount = (Long) row.get("amount");
        BigDecimal rate = (BigDecimal) row.get("fee_rate");
        PaymentFee expected = PaymentFee.calculate(amount, rate);
        assertThat(row.get("fee_amount")).isEqualTo(expected.feeAmount());
        assertThat(row.get("settlement_amount")).isEqualTo(expected.settlementAmount());
    }

    @Test
    void 커밋되지_않은_수수료율_변경은_결제에_보이지_않고_결제는_기다리지_않는다() throws Exception {
        Long first = newOrder();
        Long second = newOrder();
        CountDownLatch changed = new CountDownLatch(1);   // 변경은 했지만 아직 커밋 전
        CountDownLatch release = new CountDownLatch(1);   // 커밋 허용
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            // 관리자 트랜잭션: 3%로 변경·flush(UPDATE 실행, 매장 행 쓰기 잠금 보유) 후 커밋을 미룸
            Future<?> admin = executor.submit(() -> transactionTemplate.executeWithoutResult(status -> {
                adminStoreService.changeFeeRate(store.id(), new BigDecimal("0.03"), "admin");
                storeRepository.flush();
                changed.countDown();
                await(release);
            }));
            assertThat(changed.await(10, TimeUnit.SECONDS)).isTrue();

            // 변경이 커밋되기 전 결제 — 매장 행을 잠그지 않으므로 막히지 않고, 커밋된 값(공통 5%)을 사용
            PaymentResponse before = CompletableFuture.supplyAsync(() -> pay(first))
                    .get(10, TimeUnit.SECONDS);

            release.countDown();
            admin.get(10, TimeUnit.SECONDS);

            // 커밋 후 결제 — 3%
            PaymentResponse after = pay(second);

            Map<String, Object> beforeRow = paymentRow(before.id());
            Map<String, Object> afterRow = paymentRow(after.id());
            assertThat((BigDecimal) beforeRow.get("fee_rate")).isEqualByComparingTo("0.05");
            assertThat(beforeRow.get("fee_amount")).isEqualTo(900L);
            assertThat((BigDecimal) afterRow.get("fee_rate")).isEqualByComparingTo("0.03");
            assertThat(afterRow.get("fee_amount")).isEqualTo(540L);
            assertSelfConsistent(beforeRow);
            assertSelfConsistent(afterRow);
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void 수수료율이_계속_바뀌는_중에_동시_결제해도_각_결제는_하나의_수수료율로_일관되게_계산된다() throws Exception {
        int paymentCount = 30;
        List<Long> orderIds = new ArrayList<>();
        for (int i = 0; i < paymentCount; i++) {
            orderIds.add(newOrder());
        }
        // 공통(NULL=5%) → 3% → 10% → 0% → 해제 를 반복
        String[] rates = {"0.03", "0.10", "0", null};

        ExecutorService executor = Executors.newFixedThreadPool(9);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<?> admin = executor.submit(() -> {
                await(start);
                for (int i = 0; i < 40; i++) {
                    String rate = rates[i % rates.length];
                    if (rate == null) {
                        adminStoreService.clearFeeRate(store.id(), "admin");
                    } else {
                        adminStoreService.changeFeeRate(store.id(), new BigDecimal(rate), "admin");
                    }
                }
            });
            List<Future<PaymentResponse>> payments = new ArrayList<>();
            for (Long orderId : orderIds) {
                payments.add(executor.submit(() -> {
                    await(start);
                    return pay(orderId);
                }));
            }
            start.countDown();

            admin.get(60, TimeUnit.SECONDS);
            for (Future<PaymentResponse> payment : payments) {
                payment.get(60, TimeUnit.SECONDS);
            }
        } finally {
            executor.shutdownNow();
        }

        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT amount, fee_rate, fee_amount, settlement_amount FROM payments");
        assertThat(rows).hasSize(paymentCount);
        Set<BigDecimal> allowed = Set.of(new BigDecimal("0.0500"), new BigDecimal("0.0300"),
                new BigDecimal("0.1000"), new BigDecimal("0.0000"));
        for (Map<String, Object> row : rows) {
            assertThat(allowed).contains((BigDecimal) row.get("fee_rate")); // 실제로 설정됐던 값 중 하나
            assertSelfConsistent(row);                                     // 그 값 하나로 계산된 금액
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(30, TimeUnit.SECONDS)) {
                throw new IllegalStateException("대기 시간 초과");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
