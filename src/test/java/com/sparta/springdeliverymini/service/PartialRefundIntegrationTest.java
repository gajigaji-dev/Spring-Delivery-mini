package com.sparta.springdeliverymini.service;

import com.sparta.springdeliverymini.TestcontainersConfiguration;
import com.sparta.springdeliverymini.dto.*;
import com.sparta.springdeliverymini.entity.*;
import com.sparta.springdeliverymini.exception.ApiException;
import com.sparta.springdeliverymini.jwt.JwtProvider;
import com.sparta.springdeliverymini.repository.*;
import com.sparta.springdeliverymini.service.SettlementIntegrationTest.MutableClock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 부분 환불(V21)을 실제 PostgreSQL로 검증 — 환불 1건 단위의 정산 차감, 남은 금액 한도, 동시 요청
// (토스 결제의 부분 취소 호출은 TossPaymentIntegrationTest)
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, SettlementIntegrationTest.MutableClockConfig.class})
class PartialRefundIntegrationTest {

    private static final long PRICE = 10000; // 수수료 500, 정산액 9500 (5%)

    @Autowired private MutableClock clock;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private MockMvc mockMvc;
    @Autowired private JwtProvider jwtProvider;

    @Autowired private StoreService storeService;
    @Autowired private MenuService menuService;
    @Autowired private OrderService orderService;
    @Autowired private PaymentService paymentService;
    @Autowired private AdminPaymentService adminPaymentService;
    @Autowired private SettlementAggregationService aggregationService;

    @Autowired private UserRepository userRepository;
    @Autowired private StoreRepository storeRepository;
    @Autowired private MenuRepository menuRepository;
    @Autowired private OrderRepository orderRepository;
    @Autowired private PaymentRepository paymentRepository;
    @Autowired private PaymentRefundRepository paymentRefundRepository;
    @Autowired private SettlementRepository settlementRepository;
    @Autowired private SettlementStatusHistoryRepository settlementStatusHistoryRepository;
    @Autowired private SettlementAggregationFailureRepository settlementAggregationFailureRepository;
    @Autowired private SettlementRefundRepository settlementRefundRepository;
    @Autowired private SettlementCarryoverRepository settlementCarryoverRepository;

    private StoreResponse store;
    private MenuResponse menu;

    @BeforeEach
    void setUp() {
        clock.set(LocalDateTime.of(2026, 11, 1, 12, 0));
        userRepository.save(new User("admin", "encoded-password", Role.ADMIN));
        userRepository.save(new User("owner1", "encoded-password", Role.OWNER));
        userRepository.save(new User("customer1", "encoded-password", Role.CUSTOMER));
        store = storeService.createStore(new StoreCreateRequest("치킨집"), "owner1");
        menu = menuService.createMenu(new MenuCreateRequest(store.id(), "치킨", PRICE, null), "owner1");
    }

    @AfterEach
    void tearDown() {
        // FK 순서: 정산 연결 기록 → 환불 기록 → 정산 → 결제 → 주문 → 메뉴 → 매장 → 회원
        settlementStatusHistoryRepository.deleteAllInBatch();
        settlementAggregationFailureRepository.deleteAllInBatch();
        settlementCarryoverRepository.deleteAllInBatch();
        settlementRefundRepository.deleteAllInBatch();
        paymentRefundRepository.deleteAllInBatch();
        settlementRepository.deleteAllInBatch();
        paymentRepository.deleteAllInBatch();
        orderRepository.deleteAllInBatch();
        menuRepository.deleteAllInBatch();
        storeRepository.deleteAllInBatch();
        userRepository.deleteAllInBatch();
    }

    // deliveredAt에 배달완료된 주문의 결제 id
    private Long delivered(LocalDateTime deliveredAt) {
        clock.set(deliveredAt.minusHours(1));
        OrderResponse order = orderService.createOrder(new OrderCreateRequest(
                List.of(new OrderItemRequest(menu.id(), 1)), "06236", "서울 강남구 테헤란로 152", "101호"), "customer1");
        PaymentResponse payment = paymentService.pay(new PaymentRequest(order.id(), "CARD"), "customer1");
        orderService.statusOrder(order.id(), "owner1", OrderStatus.ORDER_ACCEPTED);
        clock.set(deliveredAt);
        orderService.statusOrder(order.id(), "owner1", OrderStatus.DELIVERY_COMPLETED);
        return payment.id();
    }

    private void aggregate(LocalDate date) {
        clock.set(date.plusDays(1).atTime(1, 10));
        aggregationService.aggregateDaily(date);
    }

    private Settlement settlement(LocalDate date) {
        return settlementRepository.findByStoreIdAndSettlementDate(store.id(), date).orElseThrow();
    }

    @Test
    void 부분_환불은_환불한_날_이후_정산에서_환불_1건씩_차감된다() {
        Long paymentId = delivered(LocalDateTime.of(2026, 11, 5, 12, 0));
        aggregate(LocalDate.of(2026, 11, 5)); // 판매 10,000 → 지급 9,500

        clock.set(LocalDateTime.of(2026, 11, 6, 10, 0));
        adminPaymentService.partialRefund(paymentId, 3000, "음료 누락", "admin");
        clock.set(LocalDateTime.of(2026, 11, 7, 10, 0));
        adminPaymentService.partialRefund(paymentId, 2000, "식은 음식", "admin");
        aggregate(LocalDate.of(2026, 11, 6));
        aggregate(LocalDate.of(2026, 11, 7));

        // 11/5 정산은 그대로 (이미 만들어진 정산은 바뀌지 않음)
        assertThat(settlement(LocalDate.of(2026, 11, 5)).getSettlementAmount()).isEqualTo(9500);
        // 11/6: 3,000원 환불(수수료 150) → 정산액 2,850 차감, 판매 없음 → 이월 2,850
        Settlement nov6 = settlement(LocalDate.of(2026, 11, 6));
        assertThat(nov6.getRefundCount()).isEqualTo(1);
        assertThat(nov6.getRefundAmount()).isEqualTo(3000);
        assertThat(nov6.getRefundFee()).isEqualTo(150);
        assertThat(nov6.getCarriedOutAmount()).isEqualTo(2850);
        // 11/7: 2,000원 환불(수수료 100) + 11/6 이월 2,850 → 이월 4,750
        Settlement nov7 = settlement(LocalDate.of(2026, 11, 7));
        assertThat(nov7.getRefundAmount()).isEqualTo(2000);
        assertThat(nov7.getCarriedInAmount()).isEqualTo(2850);
        assertThat(nov7.getCarriedOutAmount()).isEqualTo(4750);

        Payment payment = paymentRepository.findById(paymentId).orElseThrow();
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PARTIALLY_REFUNDED);
        assertThat(payment.getRefundedAmount()).isEqualTo(5000);
        // 원 결제 기록은 바뀌지 않음
        assertThat(payment.getAmount()).isEqualTo(PRICE);
        assertThat(payment.getSettlementAmount()).isEqualTo(9500);
    }

    @Test
    void 부분_환불을_모두_합쳐_전액이_되면_REFUNDED이고_차감_합계는_전액_환불과_같다() {
        Long paymentId = delivered(LocalDateTime.of(2026, 11, 5, 12, 0));
        clock.set(LocalDateTime.of(2026, 11, 6, 10, 0));

        adminPaymentService.partialRefund(paymentId, 3333, "1차", "admin");
        adminPaymentService.partialRefund(paymentId, 3333, "2차", "admin");
        adminPaymentService.refund(paymentId, "admin"); // 남은 3,334원
        aggregate(LocalDate.of(2026, 11, 6));

        Settlement nov6 = settlement(LocalDate.of(2026, 11, 6));
        assertThat(nov6.getRefundCount()).isEqualTo(3);
        assertThat(nov6.getRefundAmount()).isEqualTo(PRICE);
        assertThat(nov6.getRefundFee()).isEqualTo(500);
        assertThat(nov6.getRefundSettlementAmount()).isEqualTo(9500);
        Payment payment = paymentRepository.findById(paymentId).orElseThrow();
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.REFUNDED);
        assertThat(payment.getRefundedAt()).isEqualTo(LocalDateTime.of(2026, 11, 6, 10, 0));
    }

    @Test
    void 같은_결제에_부분_환불이_동시에_들어와도_남은_금액을_넘지_않는다() throws Exception {
        Long paymentId = delivered(LocalDateTime.of(2026, 11, 5, 12, 0));
        clock.set(LocalDateTime.of(2026, 11, 6, 10, 0));
        int threads = 8; // 3,000원씩 8건 → 3건(9,000원)만 성공, 나머지는 남은 금액 초과 400

        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(executor.submit(() -> {
                start.await();
                return adminPaymentService.partialRefund(paymentId, 3000, "동시", "admin");
            }));
        }
        start.countDown();
        int success = 0;
        int rejected = 0;
        for (Future<?> future : futures) {
            try {
                future.get(30, TimeUnit.SECONDS);
                success++;
            } catch (ExecutionException e) {
                assertThat(e.getCause()).isInstanceOf(ApiException.class);
                rejected++;
            }
        }
        executor.shutdown();

        assertThat(success).isEqualTo(3);
        assertThat(rejected).isEqualTo(5);
        assertThat(paymentRepository.findById(paymentId).orElseThrow().getRefundedAmount()).isEqualTo(9000);
        assertThat(jdbc.queryForObject("SELECT sum(amount) FROM payment_refunds WHERE payment_id = ?", Long.class, paymentId))
                .isEqualTo(9000);
    }

    @Test
    void 부분_환불_API는_201과_환불_내역을_돌려주고_남은_금액을_넘으면_400() throws Exception {
        Long paymentId = delivered(LocalDateTime.of(2026, 11, 5, 12, 0));
        clock.set(LocalDateTime.of(2026, 11, 6, 10, 0));
        String admin = "Bearer " + jwtProvider.createToken("admin", Role.ADMIN);

        mockMvc.perform(post("/api/admin/payments/" + paymentId + "/refunds")
                        .header(HttpHeaders.AUTHORIZATION, admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\": 4000, \"reason\": \"음료 누락\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.amount").value(4000))
                .andExpect(jsonPath("$.feeAmount").value(200))
                .andExpect(jsonPath("$.paymentStatus").value("PARTIALLY_REFUNDED"))
                .andExpect(jsonPath("$.refundableAmount").value(6000));

        mockMvc.perform(post("/api/admin/payments/" + paymentId + "/refunds")
                        .header(HttpHeaders.AUTHORIZATION, admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\": 6001}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REFUND_AMOUNT"));

        mockMvc.perform(post("/api/admin/payments/" + paymentId + "/refunds")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + jwtProvider.createToken("owner1", Role.OWNER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\": 100}"))
                .andExpect(status().isForbidden());
    }
}
