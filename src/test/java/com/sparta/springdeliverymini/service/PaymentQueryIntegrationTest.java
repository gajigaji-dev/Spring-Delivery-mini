package com.sparta.springdeliverymini.service;

import com.sparta.springdeliverymini.TestcontainersConfiguration;
import com.sparta.springdeliverymini.dto.*;
import com.sparta.springdeliverymini.entity.OrderStatus;
import com.sparta.springdeliverymini.entity.Role;
import com.sparta.springdeliverymini.entity.User;
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
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 결제 단건 조회 API(#53)를 실제 PostgreSQL + 보안 필터까지 통과시켜 검증
// - 본인 결제는 현재 결제·주문 상태(환불 반영)를 돌려줌 (fetch join으로 지연 로딩 오류 없이 주문 정보 포함)
// - 다른 CUSTOMER 403, 없는 결제 404, OWNER 403
// 내 결제 목록(#62): 본인 결제만 id 오름차순 페이지, 헤더 X-Has-Next, OWNER 403
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class PaymentQueryIntegrationTest {

    private static final long PRICE = 10000;

    @Autowired private MockMvc mockMvc;
    @Autowired private JwtProvider jwtProvider;

    @Autowired private StoreService storeService;
    @Autowired private MenuService menuService;
    @Autowired private OrderService orderService;
    @Autowired private PaymentService paymentService;
    @Autowired private AdminPaymentService adminPaymentService;

    @Autowired private UserRepository userRepository;
    @Autowired private StoreRepository storeRepository;
    @Autowired private MenuRepository menuRepository;
    @Autowired private OrderRepository orderRepository;
    @Autowired private PaymentRepository paymentRepository;
    @Autowired private PaymentRefundRepository paymentRefundRepository;

    private MenuResponse menu;

    @BeforeEach
    void setUp() {
        userRepository.save(new User("admin", "encoded-password", Role.ADMIN));
        userRepository.save(new User("owner1", "encoded-password", Role.OWNER));
        userRepository.save(new User("customer1", "encoded-password", Role.CUSTOMER));
        userRepository.save(new User("customer2", "encoded-password", Role.CUSTOMER));
        StoreResponse store = storeService.createStore(new StoreCreateRequest("치킨집"), "owner1");
        menu = menuService.createMenu(new MenuCreateRequest(store.id(), "치킨", PRICE, null), "owner1");
    }

    @AfterEach
    void tearDown() {
        // FK 순서대로 삭제: payments → orders → menus → stores → users
        paymentRefundRepository.deleteAllInBatch(); // 환불 1건(V21)이 payments를 참조
        paymentRepository.deleteAllInBatch();
        orderRepository.deleteAllInBatch();
        menuRepository.deleteAllInBatch();
        storeRepository.deleteAllInBatch();
        userRepository.deleteAllInBatch();
    }

    private PaymentResponse pay(String username) {
        OrderResponse order = orderService.createOrder(
                new OrderCreateRequest(List.of(new OrderItemRequest(menu.id(), 1)), "06236", "서울 강남구 테헤란로 152", "101호"), username);
        return paymentService.pay(new PaymentRequest(order.id(), "CARD"), username);
    }

    private String bearer(String username, Role role) {
        return "Bearer " + jwtProvider.createToken(username, role);
    }

    @Test
    void 본인_결제를_조회하면_결제_정보를_돌려준다() throws Exception {
        PaymentResponse payment = pay("customer1");

        mockMvc.perform(get("/api/payments/{id}", payment.id())
                        .header(HttpHeaders.AUTHORIZATION, bearer("customer1", Role.CUSTOMER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(payment.id()))
                .andExpect(jsonPath("$.orderId").value(payment.orderId()))
                .andExpect(jsonPath("$.amount").value(PRICE))
                .andExpect(jsonPath("$.paymentMethod").value("CARD"))
                .andExpect(jsonPath("$.orderStatus").value("PAYMENT_COMPLETED"))
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.cancelledAt").doesNotExist())
                .andExpect(jsonPath("$.refundedAt").doesNotExist());
    }

    @Test
    void 환불된_결제는_결제_직후가_아닌_현재_상태를_돌려준다() throws Exception {
        PaymentResponse payment = pay("customer1");
        orderService.statusOrder(payment.orderId(), "owner1", OrderStatus.ORDER_ACCEPTED);
        orderService.statusOrder(payment.orderId(), "owner1", OrderStatus.DELIVERY_COMPLETED);
        adminPaymentService.refund(payment.id(), "admin");

        mockMvc.perform(get("/api/payments/{id}", payment.id())
                        .header(HttpHeaders.AUTHORIZATION, bearer("customer1", Role.CUSTOMER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REFUNDED"))
                .andExpect(jsonPath("$.orderStatus").value("DELIVERY_COMPLETED"))
                .andExpect(jsonPath("$.refundedAt").isNotEmpty());
    }

    @Test
    void 다른_CUSTOMER의_결제를_조회하면_403() throws Exception {
        PaymentResponse payment = pay("customer1");

        mockMvc.perform(get("/api/payments/{id}", payment.id())
                        .header(HttpHeaders.AUTHORIZATION, bearer("customer2", Role.CUSTOMER)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("본인의 결제만 조회할 수 있습니다."));
    }

    @Test
    void 없는_결제를_조회하면_404() throws Exception {
        mockMvc.perform(get("/api/payments/{id}", Long.MAX_VALUE)
                        .header(HttpHeaders.AUTHORIZATION, bearer("customer1", Role.CUSTOMER)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("결제 내역이 없습니다."));
    }

    @Test
    void OWNER는_본인_매장_주문의_결제도_조회할_수_없다() throws Exception {
        PaymentResponse payment = pay("customer1");

        mockMvc.perform(get("/api/payments/{id}", payment.id())
                        .header(HttpHeaders.AUTHORIZATION, bearer("owner1", Role.OWNER)))
                .andExpect(status().isForbidden());
    }

    @Test
    void 내_결제_목록은_본인_결제만_id_오름차순으로_돌려준다() throws Exception {
        PaymentResponse first = pay("customer1");
        pay("customer2");
        PaymentResponse second = pay("customer1");

        mockMvc.perform(get("/api/payments")
                        .header(HttpHeaders.AUTHORIZATION, bearer("customer1", Role.CUSTOMER)))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Has-Next", "false"))
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].id").value(first.id()))
                .andExpect(jsonPath("$[1].id").value(second.id()))
                .andExpect(jsonPath("$[0].orderStatus").value("PAYMENT_COMPLETED"));
    }

    @Test
    void 내_결제_목록은_페이지_단위로_나뉜다() throws Exception {
        pay("customer1");
        pay("customer1");
        PaymentResponse third = pay("customer1");

        mockMvc.perform(get("/api/payments").param("page", "0").param("size", "2")
                        .header(HttpHeaders.AUTHORIZATION, bearer("customer1", Role.CUSTOMER)))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Has-Next", "true"))
                .andExpect(jsonPath("$.length()").value(2));

        mockMvc.perform(get("/api/payments").param("page", "1").param("size", "2")
                        .header(HttpHeaders.AUTHORIZATION, bearer("customer1", Role.CUSTOMER)))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Has-Next", "false"))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(third.id()));
    }

    @Test
    void 결제가_없으면_빈_배열() throws Exception {
        mockMvc.perform(get("/api/payments")
                        .header(HttpHeaders.AUTHORIZATION, bearer("customer1", Role.CUSTOMER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void 내_결제_목록의_size가_범위를_벗어나면_400() throws Exception {
        mockMvc.perform(get("/api/payments").param("size", "101")
                        .header(HttpHeaders.AUTHORIZATION, bearer("customer1", Role.CUSTOMER)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void OWNER는_결제_목록을_조회할_수_없다() throws Exception {
        mockMvc.perform(get("/api/payments")
                        .header(HttpHeaders.AUTHORIZATION, bearer("owner1", Role.OWNER)))
                .andExpect(status().isForbidden());
    }

    @Test
    void 토큰은_CUSTOMER여도_DB의_역할이_OWNER면_403() throws Exception {
        mockMvc.perform(get("/api/payments")
                        .header(HttpHeaders.AUTHORIZATION, bearer("owner1", Role.CUSTOMER)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("CUSTOMER만 결제를 조회할 수 있습니다."));
    }
}
