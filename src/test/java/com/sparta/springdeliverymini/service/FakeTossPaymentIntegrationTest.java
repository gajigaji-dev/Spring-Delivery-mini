package com.sparta.springdeliverymini.service;

import com.sparta.springdeliverymini.TestAddresses;
import com.sparta.springdeliverymini.TestcontainersConfiguration;
import com.sparta.springdeliverymini.entity.*;
import com.sparta.springdeliverymini.jwt.JwtProvider;
import com.sparta.springdeliverymini.repository.*;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 가짜 PG 모드(toss.payments.mode=fake, V21)로 앱 전체를 띄워 HTTP로 끝까지 확인 — 토스에 접속하지 않음
// 결제창 인증(가짜) → 서버 승인 → 부분 환불 → 토스(가짜) 부분 취소까지 실제 빈·DB로 연결되는지
@SpringBootTest(properties = "toss.payments.mode=fake")
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class FakeTossPaymentIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private JwtProvider jwtProvider;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private OrderService orderService;

    @Autowired private UserRepository userRepository;
    @Autowired private StoreRepository storeRepository;
    @Autowired private MenuRepository menuRepository;
    @Autowired private OrderRepository orderRepository;
    @Autowired private PaymentRepository paymentRepository;
    @Autowired private PaymentRefundRepository paymentRefundRepository;
    @Autowired private PaymentIdempotencyKeyRepository idempotencyKeyRepository;
    @Autowired private PgCancelRequestRepository pgCancelRequestRepository;

    private User customer;
    private User owner;
    private Order order;

    @BeforeEach
    void setUp() {
        customer = userRepository.save(new User("customer1", "encoded-password", Role.CUSTOMER));
        owner = userRepository.save(new User("owner1", "encoded-password", Role.OWNER));
        userRepository.save(new User("admin1", "encoded-password", Role.ADMIN));
        Store store = storeRepository.save(new Store("치킨집", owner));
        Menu menu = menuRepository.save(new Menu("후라이드 치킨", 18000, "바삭한 치킨", store));
        Order newOrder = new Order(customer, store, TestAddresses.seoul(), OrderStatus.ORDER_REQUEST);
        newOrder.addItem(menu, 2);
        order = orderRepository.save(newOrder);
    }

    @AfterEach
    void tearDown() {
        pgCancelRequestRepository.deleteAllInBatch();
        idempotencyKeyRepository.deleteAllInBatch();
        paymentRefundRepository.deleteAllInBatch();
        paymentRepository.deleteAllInBatch();
        orderRepository.deleteAllInBatch();
        menuRepository.deleteAllInBatch();
        storeRepository.deleteAllInBatch();
        userRepository.deleteAllInBatch();
        jdbc.update("DELETE FROM outbox_events");
    }

    @Test
    void 가짜_PG로_결제창_인증부터_승인_부분_환불_부분_취소까지_토스_없이_동작한다() throws Exception {
        String customerToken = "Bearer " + jwtProvider.createToken(customer.getUsername(), Role.CUSTOMER);

        mockMvc.perform(get("/api/payments/toss/config").header(HttpHeaders.AUTHORIZATION, customerToken))
                .andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.mode").value("FAKE"));

        // 1. 결제창 값 → 2. 가짜 결제창 인증(응답 유실 시나리오) → 3. 서버 승인
        JsonNode checkout = json(mockMvc.perform(post("/api/payments/toss/checkout")
                        .header(HttpHeaders.AUTHORIZATION, customerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"orderId\": " + order.getId() + "}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        String tossOrderId = checkout.get("tossOrderId").asString();
        String paymentKey = json(mockMvc.perform(post("/api/payments/fake-toss/authorize")
                        .header(HttpHeaders.AUTHORIZATION, customerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"tossOrderId": "%s", "amount": 36000, "scenario": "RESPONSE_LOST"}
                                """.formatted(tossOrderId)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString())
                .get("paymentKey").asString();

        // 승인 응답이 유실돼도 서버가 조회로 확인해 기록
        mockMvc.perform(post("/api/payments/toss/confirm")
                        .header(HttpHeaders.AUTHORIZATION, customerToken)
                        .header("Idempotency-Key", tossOrderId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"orderId": %d, "paymentKey": "%s", "tossOrderId": "%s", "amount": 36000}
                                """.formatted(order.getId(), paymentKey, tossOrderId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.orderStatus").value("PAYMENT_COMPLETED"));

        // 4. 배달 완료 → 관리자 부분 환불 → 가짜 PG 부분 취소 (커밋 후 즉시 1회 시도)
        orderService.statusOrder(order.getId(), owner.getUsername(), OrderStatus.ORDER_ACCEPTED);
        orderService.statusOrder(order.getId(), owner.getUsername(), OrderStatus.DELIVERY_COMPLETED);
        Long paymentId = paymentRepository.findByOrderId(order.getId()).orElseThrow().getId();
        mockMvc.perform(post("/api/admin/payments/" + paymentId + "/refunds")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + jwtProvider.createToken("admin1", Role.ADMIN))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\": 6000, \"reason\": \"음료 누락\"}"))
                .andExpect(status().isCreated());

        PgCancelRequest cancel = pgCancelRequestRepository.findAllByPaymentKeyOrderByIdAsc(paymentKey).getFirst();
        assertThat(cancel.getStatus()).isEqualTo(PgCancelStatus.DONE);
        assertThat(cancel.getBalanceAfter()).isEqualTo(30000);
    }

    @Test
    void 가짜_PG_모드에서도_승인_금액이_주문과_다르면_승인하지_않는다() throws Exception {
        String customerToken = "Bearer " + jwtProvider.createToken(customer.getUsername(), Role.CUSTOMER);
        String tossOrderId = json(mockMvc.perform(post("/api/payments/toss/checkout")
                        .header(HttpHeaders.AUTHORIZATION, customerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"orderId\": " + order.getId() + "}"))
                .andReturn().getResponse().getContentAsString()).get("tossOrderId").asString();
        // 화면이 금액을 바꿔 결제창을 연 경우
        String paymentKey = json(mockMvc.perform(post("/api/payments/fake-toss/authorize")
                        .header(HttpHeaders.AUTHORIZATION, customerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tossOrderId\": \"" + tossOrderId + "\", \"amount\": 100}"))
                .andReturn().getResponse().getContentAsString()).get("paymentKey").asString();

        mockMvc.perform(post("/api/payments/toss/confirm")
                        .header(HttpHeaders.AUTHORIZATION, customerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"orderId": %d, "paymentKey": "%s", "tossOrderId": "%s", "amount": 100}
                                """.formatted(order.getId(), paymentKey, tossOrderId)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PAYMENT_AMOUNT_MISMATCH"));
        assertThat(paymentRepository.count()).isZero();
    }

    private JsonNode json(String body) {
        return objectMapper.readTree(body);
    }
}
