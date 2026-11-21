package com.sparta.springdeliverymini.service;

import com.sparta.springdeliverymini.TestAddresses;
import com.sparta.springdeliverymini.TestcontainersConfiguration;
import com.sparta.springdeliverymini.config.TossPaymentsProperties;
import com.sparta.springdeliverymini.dto.TossCheckoutResponse;
import com.sparta.springdeliverymini.entity.*;
import com.sparta.springdeliverymini.jwt.JwtProvider;
import com.sparta.springdeliverymini.pg.TossApiException;
import com.sparta.springdeliverymini.pg.TossPayment;
import com.sparta.springdeliverymini.pg.TossPaymentsClient;
import com.sparta.springdeliverymini.pg.TossUnknownResultException;
import com.sparta.springdeliverymini.repository.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.matchesPattern;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 토스페이먼츠 테스트 모드 결제를 실제 PostgreSQL·Redis로 검증 (V20, docs/api.md 토스페이먼츠 결제 장)
// - 토스 API는 부르지 않음: TossPaymentsClient를 가짜로 바꿔 승인·조회·취소 결과(성공·거절·시간 초과)를 재현
//   (HTTP 요청 형식·응답 해석은 pg.TossPaymentsClientTest가 로컬 HTTP 서버로 확인)
// - @Transactional 없이 실제 커밋 — 승인 기록·취소 요청은 각자 트랜잭션이고, 취소 호출은 커밋 후에 일어남
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class TossPaymentIntegrationTest {

    private static final long TOTAL = 36000; // 18,000원 × 2
    private static final String PAYMENT_KEY = "tgen_20261121_test_payment_key";

    @MockitoBean private TossPaymentsClient tossPaymentsClient;

    @Autowired private MockMvc mockMvc;
    @Autowired private JwtProvider jwtProvider;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private Clock clock;
    @Autowired private StringRedisTemplate redisTemplate;
    @Autowired private TossPaymentService tossPaymentService;
    @Autowired private OrderService orderService;
    @Autowired private AdminPaymentService adminPaymentService;
    @Autowired private PgCancelProcessor pgCancelProcessor;

    @Autowired private UserRepository userRepository;
    @Autowired private StoreRepository storeRepository;
    @Autowired private MenuRepository menuRepository;
    @Autowired private OrderRepository orderRepository;
    @Autowired private PaymentRepository paymentRepository;
    @Autowired private PaymentIdempotencyKeyRepository idempotencyKeyRepository;
    @Autowired private PgCancelRequestRepository pgCancelRequestRepository;
    @Autowired private PaymentRefundRepository paymentRefundRepository;

    private User customer;
    private User owner;
    private Store store;
    private Menu menu;

    @BeforeEach
    void setUp() {
        given(tossPaymentsClient.enabled()).willReturn(true);
        given(tossPaymentsClient.clientKey()).willReturn("test_ck_dummy");
        given(tossPaymentsClient.mode()).willReturn(TossPaymentsProperties.Mode.REAL);
        customer = userRepository.save(new User("customer1", "encoded-password", Role.CUSTOMER));
        owner = userRepository.save(new User("owner1", "encoded-password", Role.OWNER));
        store = storeRepository.save(new Store("치킨집", owner));
        menu = menuRepository.save(new Menu("후라이드 치킨", 18000, "바삭한 치킨", store));
    }

    @AfterEach
    void tearDown() {
        // FK 순서: PG 취소 요청·멱등성 기록(payments 참조) → payments → orders → menus → stores → users
        pgCancelRequestRepository.deleteAllInBatch();
        idempotencyKeyRepository.deleteAllInBatch();
        paymentRefundRepository.deleteAllInBatch();
        paymentRepository.deleteAllInBatch();
        orderRepository.deleteAllInBatch();
        menuRepository.deleteAllInBatch();
        storeRepository.deleteAllInBatch();
        userRepository.deleteAllInBatch();
        Set<String> keys = redisTemplate.keys(PaymentIdempotencyService.KEY_PREFIX + "*");
        if (!keys.isEmpty()) {
            redisTemplate.delete(keys);
        }
    }

    // ===== 설정·결제창 값 =====

    @Test
    void 키가_설정되면_토스_결제_사용_여부와_공개_클라이언트_키만_돌려준다() throws Exception {
        mockMvc.perform(get("/api/payments/toss/config").header(HttpHeaders.AUTHORIZATION, bearer(customer, Role.CUSTOMER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.clientKey").value("test_ck_dummy"))
                .andExpect(jsonPath("$.mode").value("REAL"))
                .andExpect(jsonPath("$.secretKey").doesNotExist());
    }

    @Test
    void 사장님은_토스_결제_설정을_조회할_수_없다() throws Exception {
        mockMvc.perform(get("/api/payments/toss/config").header(HttpHeaders.AUTHORIZATION, bearer(owner, Role.OWNER)))
                .andExpect(status().isForbidden());
    }

    @Test
    void 체크아웃은_주문_총액과_이_주문에_발급한_토스_주문번호를_돌려주고_아무것도_저장하지_않는다() throws Exception {
        Order order = order();

        mockMvc.perform(post("/api/payments/toss/checkout")
                        .header(HttpHeaders.AUTHORIZATION, bearer(customer, Role.CUSTOMER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"orderId\": " + order.getId() + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.amount").value(TOTAL))
                .andExpect(jsonPath("$.orderName").value("후라이드 치킨"))
                .andExpect(jsonPath("$.tossOrderId").value(matchesPattern(
                        "order-" + order.getId() + "-[0-9a-f]{16}")))
                .andExpect(jsonPath("$.customerKey").value(matchesPattern("cust_[0-9a-f]{40}")));

        assertThat(paymentRepository.count()).isZero();
    }

    @Test
    void 키가_없으면_체크아웃은_503_PG_NOT_CONFIGURED() throws Exception {
        given(tossPaymentsClient.enabled()).willReturn(false);
        Order order = order();

        mockMvc.perform(post("/api/payments/toss/checkout")
                        .header(HttpHeaders.AUTHORIZATION, bearer(customer, Role.CUSTOMER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"orderId\": " + order.getId() + "}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("PG_NOT_CONFIGURED"));
    }

    // ===== 승인 =====

    @Test
    void 승인하면_토스_결제_키와_함께_결제가_기록되고_주문이_결제완료가_된다() throws Exception {
        Order order = order();
        String tossOrderId = checkout(order);
        given(tossPaymentsClient.confirm(PAYMENT_KEY, tossOrderId, TOTAL, "confirm-" + PAYMENT_KEY))
                .willReturn(done(tossOrderId, TOTAL));

        confirm(order.getId(), tossOrderId, TOTAL, null)
                .andExpect(status().isCreated())
                .andExpect(header().doesNotExist("Idempotent-Replayed"))
                .andExpect(jsonPath("$.amount").value(TOTAL))
                .andExpect(jsonPath("$.orderStatus").value("PAYMENT_COMPLETED"));

        Map<String, Object> row = jdbc.queryForMap("SELECT * FROM payments WHERE order_id = ?", order.getId());
        assertThat(row.get("pg_provider")).isEqualTo("TOSS");
        assertThat(row.get("pg_payment_key")).isEqualTo(PAYMENT_KEY);
        assertThat(row.get("pg_order_id")).isEqualTo(tossOrderId);
        assertThat(row.get("pg_approved_at")).isNotNull();
        assertThat(row.get("amount")).isEqualTo(TOTAL);
        assertThat(row.get("fee_amount")).isEqualTo(1800L); // 수수료 계산은 모의 결제와 같음 (5%)
        assertThat(orderRepository.findById(order.getId()).orElseThrow().getStatus())
                .isEqualTo(OrderStatus.PAYMENT_COMPLETED);
    }

    @Test
    void 같은_결제_키로_다시_승인을_요청하면_토스를_다시_부르지_않고_처음_결과를_돌려준다() throws Exception {
        Order order = order();
        String tossOrderId = checkout(order);
        given(tossPaymentsClient.confirm(anyString(), anyString(), anyLong(), anyString()))
                .willReturn(done(tossOrderId, TOTAL));

        String first = confirm(order.getId(), tossOrderId, TOTAL, null)
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String second = confirm(order.getId(), tossOrderId, TOTAL, null)
                .andExpect(status().isCreated())
                .andExpect(header().string("Idempotent-Replayed", "true"))
                .andReturn().getResponse().getContentAsString();

        assertThat(second).isEqualTo(first);
        assertThat(paymentRepository.count()).isEqualTo(1);
        verify(tossPaymentsClient, times(1)).confirm(anyString(), anyString(), anyLong(), anyString());
    }

    @Test
    void Idempotency_Key가_있으면_기존_결제_멱등성_기록에도_남고_같은_키_재시도는_처음_응답을_재전송한다() throws Exception {
        Order order = order();
        String tossOrderId = checkout(order);
        given(tossPaymentsClient.confirm(anyString(), anyString(), anyLong(), anyString()))
                .willReturn(done(tossOrderId, TOTAL));

        String first = confirm(order.getId(), tossOrderId, TOTAL, tossOrderId)
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String second = confirm(order.getId(), tossOrderId, TOTAL, tossOrderId)
                .andExpect(status().isCreated())
                .andExpect(header().string("Idempotent-Replayed", "true"))
                .andReturn().getResponse().getContentAsString();

        assertThat(second).isEqualTo(first);
        assertThat(idempotencyKeyRepository.count()).isEqualTo(1);
        verify(tossPaymentsClient, times(1)).confirm(anyString(), anyString(), anyLong(), anyString());
    }

    @Test
    void 금액이_주문_총액과_다르면_토스_승인을_호출하지_않고_400_PAYMENT_AMOUNT_MISMATCH() throws Exception {
        Order order = order();
        String tossOrderId = checkout(order);

        confirm(order.getId(), tossOrderId, 100, null)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PAYMENT_AMOUNT_MISMATCH"));

        verify(tossPaymentsClient, never()).confirm(anyString(), anyString(), anyLong(), any());
        assertThat(paymentRepository.count()).isZero();
        assertThat(orderRepository.findById(order.getId()).orElseThrow().getStatus()).isEqualTo(OrderStatus.ORDER_REQUEST);
    }

    @Test
    void 다른_주문에_발급한_토스_주문번호면_승인하지_않고_400_INVALID_PG_ORDER_ID() throws Exception {
        Order order = order();
        Order other = order();
        String otherTossOrderId = checkout(other);

        confirm(order.getId(), otherTossOrderId, TOTAL, null)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PG_ORDER_ID"));

        verify(tossPaymentsClient, never()).confirm(anyString(), anyString(), anyLong(), any());
    }

    @Test
    void 토스가_승인을_거절하면_400_PG_PAYMENT_REJECTED이고_주문은_결제_전_그대로다() throws Exception {
        Order order = order();
        String tossOrderId = checkout(order);
        given(tossPaymentsClient.confirm(anyString(), anyString(), anyLong(), anyString()))
                .willThrow(new TossApiException(403, "REJECT_CARD_COMPANY", "결제 승인이 거절되었습니다."));

        confirm(order.getId(), tossOrderId, TOTAL, null)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PG_PAYMENT_REJECTED"));

        assertThat(paymentRepository.count()).isZero();
        assertThat(pgCancelRequestRepository.count()).isZero(); // 승인되지 않았으므로 취소할 것도 없음
        assertThat(orderRepository.findById(order.getId()).orElseThrow().getStatus()).isEqualTo(OrderStatus.ORDER_REQUEST);
    }

    @Test
    void 승인_응답이_시간_초과되면_결제_조회로_확인해_승인된_결제를_기록한다() throws Exception {
        Order order = order();
        String tossOrderId = checkout(order);
        given(tossPaymentsClient.confirm(anyString(), anyString(), anyLong(), anyString()))
                .willThrow(new TossUnknownResultException("토스페이먼츠 응답 시간 초과 operation=confirm"));
        given(tossPaymentsClient.getPayment(PAYMENT_KEY)).willReturn(done(tossOrderId, TOTAL));

        confirm(order.getId(), tossOrderId, TOTAL, null).andExpect(status().isCreated());

        assertThat(paymentRepository.findWithOrderByPgPaymentKey(PAYMENT_KEY)).isPresent();
    }

    @Test
    void 승인_결과를_확인하지_못하면_503_PG_RESULT_UNKNOWN이고_같은_요청을_다시_보내면_기록된다() throws Exception {
        Order order = order();
        String tossOrderId = checkout(order);
        given(tossPaymentsClient.confirm(anyString(), anyString(), anyLong(), anyString()))
                .willThrow(new TossUnknownResultException("토스페이먼츠 응답 시간 초과 operation=confirm"))
                .willReturn(done(tossOrderId, TOTAL)); // 재시도는 같은 멱등 키 → 토스가 처음 승인 결과를 돌려줌
        given(tossPaymentsClient.getPayment(PAYMENT_KEY))
                .willReturn(new TossPayment(PAYMENT_KEY, tossOrderId, "IN_PROGRESS", TOTAL, TOTAL, "카드", null));

        confirm(order.getId(), tossOrderId, TOTAL, null)
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("PG_RESULT_UNKNOWN"));
        assertThat(paymentRepository.count()).isZero();
        assertThat(pgCancelRequestRepository.count()).isZero(); // 결과를 모를 때 취소하지 않음

        confirm(order.getId(), tossOrderId, TOTAL, null).andExpect(status().isCreated());
        assertThat(paymentRepository.count()).isEqualTo(1);
        // 두 번 모두 같은 멱등 키로 승인 요청
        verify(tossPaymentsClient, times(2)).confirm(PAYMENT_KEY, tossOrderId, TOTAL, "confirm-" + PAYMENT_KEY);
    }

    @Test
    void 승인_중에_주문이_취소되면_결제를_기록하지_않고_토스_결제를_보상_취소한다() throws Exception {
        Order order = order();
        String tossOrderId = checkout(order);
        given(tossPaymentsClient.confirm(anyString(), anyString(), anyLong(), anyString())).willAnswer(invocation -> {
            orderService.cancelOrder(order.getId(), customer.getUsername()); // 승인을 기다리는 사이 다른 탭에서 취소
            return done(tossOrderId, TOTAL);
        });
        given(tossPaymentsClient.cancel(eq(PAYMENT_KEY), anyString(), any(), anyString())).willReturn(canceled(tossOrderId));

        confirm(order.getId(), tossOrderId, TOTAL, null)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ORDER_ALREADY_CANCELLED"));

        assertThat(paymentRepository.count()).isZero();
        PgCancelRequest request = pgCancelRequestRepository.findAllByPaymentKeyOrderByIdAsc(PAYMENT_KEY).getFirst();
        assertThat(request.getPayment()).isNull();
        assertThat(request.getAmount()).isEqualTo(TOTAL);
        assertThat(request.getStatus()).isEqualTo(PgCancelStatus.DONE); // 커밋 직후 1회 시도에서 완료
        verify(tossPaymentsClient).cancel(eq(PAYMENT_KEY), anyString(), eq(TOTAL), eq("cancel-" + request.getId() + "-1"));
    }

    // ===== 취소·환불 =====

    @Test
    void 결제완료_주문을_취소하면_커밋_후_토스_결제_취소를_호출하고_DONE으로_기록한다() throws Exception {
        Order order = paidByToss();
        given(tossPaymentsClient.cancel(eq(PAYMENT_KEY), anyString(), any(), anyString())).willReturn(canceled("x"));

        orderService.cancelOrder(order.getId(), customer.getUsername());

        Payment payment = paymentRepository.findByOrderId(order.getId()).orElseThrow();
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.CANCELLED);
        PgCancelRequest request = pgCancelRequestRepository.findAllByPaymentKeyOrderByIdAsc(PAYMENT_KEY).getFirst();
        assertThat(request.getStatus()).isEqualTo(PgCancelStatus.DONE);
        assertThat(request.getAttempts()).isEqualTo(1);
        assertThat(request.getCompletedAt()).isNotNull();
        verify(tossPaymentsClient, times(1)).cancel(eq(PAYMENT_KEY), eq("고객 주문 취소"), eq(TOTAL), anyString());
    }

    @Test
    void 토스_취소가_시간_초과되면_주문_취소는_그대로_성공하고_재시도는_잔액부터_확인해_이미_취소됐으면_다시_보내지_않는다()
            throws Exception {
        Order order = paidByToss();
        given(tossPaymentsClient.cancel(eq(PAYMENT_KEY), anyString(), any(), anyString()))
                .willThrow(new TossUnknownResultException("토스페이먼츠 응답 시간 초과 operation=cancel"));
        // 실제로는 첫 취소가 토스에 반영됨 (응답만 유실)
        given(tossPaymentsClient.getPayment(PAYMENT_KEY)).willReturn(canceled("x"));

        orderService.cancelOrder(order.getId(), customer.getUsername());

        assertThat(orderRepository.findById(order.getId()).orElseThrow().getStatus()).isEqualTo(OrderStatus.ORDER_CANCELLED);
        PgCancelRequest pending = pgCancelRequestRepository.findAllByPaymentKeyOrderByIdAsc(PAYMENT_KEY).getFirst();
        assertThat(pending.getStatus()).isEqualTo(PgCancelStatus.PENDING);
        assertThat(pending.getAttempts()).isEqualTo(1);
        assertThat(pending.getLastError()).contains("시간 초과");
        // 백오프(10초) 전에는 주기 작업이 가져가지 않음
        assertThat(pgCancelProcessor.processDue()).isZero();

        makeDue(pending);
        assertThat(pgCancelProcessor.processDue()).isEqualTo(1);

        PgCancelRequest done = pgCancelRequestRepository.findById(pending.getId()).orElseThrow();
        assertThat(done.getStatus()).isEqualTo(PgCancelStatus.DONE);
        assertThat(done.getAttempts()).isEqualTo(2);
        // 두 번째 시도는 잔액(0 ≤ 목표 0)으로 반영을 확인했으므로 취소를 다시 보내지 않음 → 두 번 환불되지 않음
        verify(tossPaymentsClient, times(1)).cancel(eq(PAYMENT_KEY), anyString(), any(), anyString());
    }

    @Test
    void 토스_취소가_시간_초과됐고_실제로도_반영되지_않았으면_다음_시도에서_새_멱등_키로_다시_취소한다() throws Exception {
        Order order = paidByToss();
        given(tossPaymentsClient.cancel(eq(PAYMENT_KEY), anyString(), any(), anyString()))
                .willThrow(new TossUnknownResultException("토스페이먼츠 응답 시간 초과 operation=cancel"))
                .willReturn(canceled("x"));
        given(tossPaymentsClient.getPayment(PAYMENT_KEY)).willReturn(done("x", TOTAL)); // 잔액 그대로 = 반영 안 됨

        orderService.cancelOrder(order.getId(), customer.getUsername());
        PgCancelRequest pending = pgCancelRequestRepository.findAllByPaymentKeyOrderByIdAsc(PAYMENT_KEY).getFirst();
        makeDue(pending);
        pgCancelProcessor.processDue();

        assertThat(pgCancelRequestRepository.findById(pending.getId()).orElseThrow().getStatus())
                .isEqualTo(PgCancelStatus.DONE);
        // 시도마다 멱등 키가 다름 — 같은 키면 토스가 첫 오류(시간 초과 당시 결과)를 계속 돌려줄 수 있음
        verify(tossPaymentsClient).cancel(PAYMENT_KEY, "고객 주문 취소", TOTAL, "cancel-" + pending.getId() + "-1");
        verify(tossPaymentsClient).cancel(PAYMENT_KEY, "고객 주문 취소", TOTAL, "cancel-" + pending.getId() + "-2");
    }

    @Test
    void 모의_결제_주문을_취소하면_토스를_부르지_않는다() {
        Order order = order();
        jdbc.update("UPDATE orders SET status = 'PAYMENT_COMPLETED' WHERE id = ?", order.getId());
        jdbc.update("""
                INSERT INTO payments (order_id, amount, fee_rate, fee_amount, settlement_amount, payment_method, paid_at,
                                      status, created_at, updated_at)
                VALUES (?, ?, 0.05, 1800, 34200, 'CARD', now(), 'COMPLETED', now(), now())
                """, order.getId(), TOTAL);

        orderService.cancelOrder(order.getId(), customer.getUsername());

        assertThat(pgCancelRequestRepository.count()).isZero();
        verify(tossPaymentsClient, never()).cancel(anyString(), anyString(), any(), anyString());
    }

    @Test
    void 관리자_환불도_토스_결제_전액_취소를_요청한다() throws Exception {
        Order order = paidByToss();
        User admin = userRepository.save(new User("admin1", "encoded-password", Role.ADMIN));
        orderService.statusOrder(order.getId(), owner.getUsername(), OrderStatus.ORDER_ACCEPTED);
        orderService.statusOrder(order.getId(), owner.getUsername(), OrderStatus.DELIVERY_COMPLETED);
        given(tossPaymentsClient.cancel(eq(PAYMENT_KEY), anyString(), any(), anyString())).willReturn(canceled("x"));
        Long paymentId = paymentRepository.findByOrderId(order.getId()).orElseThrow().getId();

        adminPaymentService.refund(paymentId, admin.getUsername());

        PgCancelRequest request = pgCancelRequestRepository.findAllByPaymentKeyOrderByIdAsc(PAYMENT_KEY).getFirst();
        assertThat(request.getStatus()).isEqualTo(PgCancelStatus.DONE);
        assertThat(request.getReason()).isEqualTo("관리자 환불");
    }

    @Test
    void 관리자_부분_환불은_환불_금액만큼_토스_부분_취소를_요청한다() throws Exception {
        Order order = deliveredByToss();
        given(tossPaymentsClient.cancel(eq(PAYMENT_KEY), anyString(), any(), anyString()))
                .willReturn(new TossPayment(PAYMENT_KEY, "x", "PARTIAL_CANCELED", TOTAL, TOTAL - 3000, "카드", null));
        Long paymentId = paymentRepository.findByOrderId(order.getId()).orElseThrow().getId();

        adminPaymentService.partialRefund(paymentId, 3000, "음료 누락", "admin1");

        PgCancelRequest request = pgCancelRequestRepository.findAllByPaymentKeyOrderByIdAsc(PAYMENT_KEY).getFirst();
        assertThat(request.getAmount()).isEqualTo(3000);
        assertThat(request.getBalanceAfter()).isEqualTo(TOTAL - 3000);
        assertThat(request.getPaymentRefund()).isNotNull();
        assertThat(request.getStatus()).isEqualTo(PgCancelStatus.DONE);
        verify(tossPaymentsClient).cancel(eq(PAYMENT_KEY), eq("음료 누락"), eq(3000L), anyString());
    }

    @Test
    void 같은_결제의_부분_취소는_앞_요청이_끝나야_다음_요청을_보낸다() throws Exception {
        Order order = deliveredByToss();
        given(tossPaymentsClient.cancel(eq(PAYMENT_KEY), anyString(), any(), anyString()))
                .willThrow(new TossUnknownResultException("토스페이먼츠 응답 시간 초과 operation=cancel"));
        Long paymentId = paymentRepository.findByOrderId(order.getId()).orElseThrow().getId();

        adminPaymentService.partialRefund(paymentId, 3000, "1차", "admin1"); // 토스 결과 불명 → PENDING
        adminPaymentService.partialRefund(paymentId, 2000, "2차", "admin1"); // 앞 요청이 안 끝나 대기

        List<PgCancelRequest> requests = pgCancelRequestRepository.findAllByPaymentKeyOrderByIdAsc(PAYMENT_KEY);
        assertThat(requests).extracting(PgCancelRequest::getAttempts).containsExactly(1, 0);
        verify(tossPaymentsClient, times(1)).cancel(eq(PAYMENT_KEY), anyString(), any(), anyString());

        // 1차가 실제로는 반영됨(잔액 33,000) → 재시도에서 DONE, 그다음 2차가 잔액 31,000을 목표로 전송
        given(tossPaymentsClient.getPayment(PAYMENT_KEY))
                .willReturn(new TossPayment(PAYMENT_KEY, "x", "PARTIAL_CANCELED", TOTAL, TOTAL - 3000, "카드", null));
        given(tossPaymentsClient.cancel(eq(PAYMENT_KEY), eq("2차"), eq(2000L), anyString()))
                .willReturn(new TossPayment(PAYMENT_KEY, "x", "PARTIAL_CANCELED", TOTAL, TOTAL - 5000, "카드", null));
        makeDue(requests.get(0));
        pgCancelProcessor.processDue(); // 1차 DONE (취소를 다시 보내지 않음)
        pgCancelProcessor.processDue(); // 2차 전송

        assertThat(pgCancelRequestRepository.findAllByPaymentKeyOrderByIdAsc(PAYMENT_KEY))
                .extracting(PgCancelRequest::getStatus).containsExactly(PgCancelStatus.DONE, PgCancelStatus.DONE);
        verify(tossPaymentsClient, times(1)).cancel(eq(PAYMENT_KEY), eq("1차"), any(), anyString());
        verify(tossPaymentsClient, times(1)).cancel(eq(PAYMENT_KEY), eq("2차"), eq(2000L), anyString());
    }

    @Test
    void 같은_결제의_전액_취소_요청은_한_번만_쌓인다_DB_제약() {
        jdbc.update("""
                INSERT INTO pg_cancel_requests (pg_provider, payment_key, amount, balance_after, reason, status, attempts,
                                                next_attempt_at, requested_at)
                VALUES ('TOSS', ?, ?, 0, '테스트', 'PENDING', 0, now(), now())
                """, PAYMENT_KEY, TOTAL);

        assertThatThrownBy(() -> jdbc.update("""
                        INSERT INTO pg_cancel_requests (pg_provider, payment_key, amount, balance_after, reason, status,
                                                        attempts, next_attempt_at, requested_at)
                        VALUES ('TOSS', ?, ?, 0, '테스트', 'PENDING', 0, now(), now())
                        """, PAYMENT_KEY, TOTAL))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    // ===== 도우미 =====

    // 다음 시도 시각이 지난 것으로 만듦 (백오프를 기다리지 않고 주기 작업 실행)
    private void makeDue(PgCancelRequest request) {
        jdbc.update("UPDATE pg_cancel_requests SET next_attempt_at = ? WHERE id = ?",
                LocalDateTime.now(clock).minusSeconds(1), request.getId());
    }

    private Order order() {
        Order newOrder = new Order(customer, store, TestAddresses.seoul(), OrderStatus.ORDER_REQUEST);
        newOrder.addItem(menu, 2);
        return orderRepository.save(newOrder);
    }

    private String checkout(Order order) {
        TossCheckoutResponse checkout = tossPaymentService.checkout(order.getId(), customer.getUsername());
        return checkout.tossOrderId();
    }

    // 토스로 결제하고 배달까지 끝난 주문 + 관리자 admin1
    private Order deliveredByToss() throws Exception {
        Order order = paidByToss();
        userRepository.save(new User("admin1", "encoded-password", Role.ADMIN));
        orderService.statusOrder(order.getId(), owner.getUsername(), OrderStatus.ORDER_ACCEPTED);
        orderService.statusOrder(order.getId(), owner.getUsername(), OrderStatus.DELIVERY_COMPLETED);
        return order;
    }

    // 토스로 결제완료된 주문
    private Order paidByToss() throws Exception {
        Order order = order();
        String tossOrderId = checkout(order);
        given(tossPaymentsClient.confirm(anyString(), anyString(), anyLong(), anyString()))
                .willReturn(done(tossOrderId, TOTAL));
        confirm(order.getId(), tossOrderId, TOTAL, null).andExpect(status().isCreated());
        return order;
    }

    private ResultActions confirm(Long orderId, String tossOrderId, long amount, String idempotencyKey) throws Exception {
        var request = post("/api/payments/toss/confirm")
                .header(HttpHeaders.AUTHORIZATION, bearer(customer, Role.CUSTOMER))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"orderId": %d, "paymentKey": "%s", "tossOrderId": "%s", "amount": %d}
                        """.formatted(orderId, PAYMENT_KEY, tossOrderId, amount));
        if (idempotencyKey != null) {
            request.header("Idempotency-Key", idempotencyKey);
        }
        return mockMvc.perform(request);
    }

    private String bearer(User user, Role role) {
        return "Bearer " + jwtProvider.createToken(user.getUsername(), role);
    }

    private static TossPayment done(String tossOrderId, long amount) {
        return new TossPayment(PAYMENT_KEY, tossOrderId, "DONE", amount, amount, "카드", "2026-11-21T12:00:00+09:00");
    }

    private static TossPayment canceled(String tossOrderId) {
        return new TossPayment(PAYMENT_KEY, tossOrderId, "CANCELED", TOTAL, 0L, "카드", "2026-11-21T12:00:00+09:00");
    }
}
