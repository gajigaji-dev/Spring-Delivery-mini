package com.sparta.springdeliverymini.service;

import com.sparta.springdeliverymini.TestcontainersConfiguration;
import com.sparta.springdeliverymini.dto.*;
import com.sparta.springdeliverymini.entity.Role;
import com.sparta.springdeliverymini.entity.User;
import com.sparta.springdeliverymini.jwt.JwtProvider;
import com.sparta.springdeliverymini.repository.*;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 주문 단건 조회 API(#62)를 실제 PostgreSQL + 보안 필터까지 통과시켜 검증
// - CUSTOMER는 본인 주문, OWNER는 본인 매장 주문만 (다른 회원·다른 매장 403, 없음 404, ADMIN 403)
// - 항목·결제 정보가 함께 오고, 항목 수와 관계없이 쿼리 수가 일정 (N+1 없음)
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class OrderQueryIntegrationTest {

    private static final long CHICKEN_PRICE = 18000;
    private static final long COLA_PRICE = 2000;

    @Autowired private MockMvc mockMvc;
    @Autowired private JwtProvider jwtProvider;
    @Autowired private EntityManagerFactory entityManagerFactory;

    @Autowired private StoreService storeService;
    @Autowired private MenuService menuService;
    @Autowired private OrderService orderService;
    @Autowired private PaymentService paymentService;

    @Autowired private UserRepository userRepository;
    @Autowired private StoreRepository storeRepository;
    @Autowired private MenuRepository menuRepository;
    @Autowired private OrderRepository orderRepository;
    @Autowired private PaymentRepository paymentRepository;

    private MenuResponse chicken;
    private MenuResponse cola;

    @BeforeEach
    void setUp() {
        userRepository.save(new User("admin", "encoded-password", Role.ADMIN));
        userRepository.save(new User("owner1", "encoded-password", Role.OWNER));
        userRepository.save(new User("owner2", "encoded-password", Role.OWNER));
        userRepository.save(new User("customer1", "encoded-password", Role.CUSTOMER));
        userRepository.save(new User("customer2", "encoded-password", Role.CUSTOMER));
        StoreResponse store = storeService.createStore(new StoreCreateRequest("치킨집"), "owner1");
        storeService.createStore(new StoreCreateRequest("피자집"), "owner2");
        chicken = menuService.createMenu(new MenuCreateRequest(store.id(), "치킨", CHICKEN_PRICE, null), "owner1");
        cola = menuService.createMenu(new MenuCreateRequest(store.id(), "콜라", COLA_PRICE, null), "owner1");
    }

    @AfterEach
    void tearDown() {
        // FK 순서대로 삭제: payments → orders(order_items는 ON DELETE CASCADE로 함께 삭제) → menus → stores → users
        paymentRepository.deleteAllInBatch();
        orderRepository.deleteAllInBatch();
        menuRepository.deleteAllInBatch();
        storeRepository.deleteAllInBatch();
        userRepository.deleteAllInBatch();
    }

    private OrderResponse order(String username) {
        return orderService.createOrder(new OrderCreateRequest(List.of(
                new OrderItemRequest(chicken.id(), 2),
                new OrderItemRequest(cola.id(), 3)), "06236", "서울 강남구 테헤란로 152", "101호"), username);
    }

    private String bearer(String username, Role role) {
        return "Bearer " + jwtProvider.createToken(username, role);
    }

    @Test
    void CUSTOMER는_본인_주문을_항목과_함께_조회하고_결제_전이면_결제는_null() throws Exception {
        OrderResponse order = order("customer1");

        mockMvc.perform(get("/api/orders/{id}", order.id())
                        .header(HttpHeaders.AUTHORIZATION, bearer("customer1", Role.CUSTOMER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(order.id()))
                .andExpect(jsonPath("$.storeName").value("치킨집"))
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.totalPrice").value(CHICKEN_PRICE * 2 + COLA_PRICE * 3))
                .andExpect(jsonPath("$.status").value("ORDER_REQUEST"))
                .andExpect(jsonPath("$.payment").doesNotExist());
    }

    @Test
    void 결제한_주문은_결제_정보를_함께_돌려준다() throws Exception {
        OrderResponse order = order("customer1");
        PaymentResponse payment = paymentService.pay(new PaymentRequest(order.id(), "CARD"), "customer1");

        mockMvc.perform(get("/api/orders/{id}", order.id())
                        .header(HttpHeaders.AUTHORIZATION, bearer("customer1", Role.CUSTOMER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PAYMENT_COMPLETED"))
                .andExpect(jsonPath("$.payment.id").value(payment.id()))
                .andExpect(jsonPath("$.payment.amount").value(order.totalPrice()))
                .andExpect(jsonPath("$.payment.paymentMethod").value("CARD"))
                .andExpect(jsonPath("$.payment.status").value("COMPLETED"))
                // 수수료·정산액은 주문 응답에 포함하지 않음
                .andExpect(jsonPath("$.payment.feeAmount").doesNotExist())
                .andExpect(jsonPath("$.payment.settlementAmount").doesNotExist());
    }

    @Test
    void OWNER는_본인_매장에_들어온_주문을_조회할_수_있다() throws Exception {
        OrderResponse order = order("customer1");

        mockMvc.perform(get("/api/orders/{id}", order.id())
                        .header(HttpHeaders.AUTHORIZATION, bearer("owner1", Role.OWNER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(order.id()));
    }

    @Test
    void 다른_CUSTOMER의_주문을_조회하면_403() throws Exception {
        OrderResponse order = order("customer1");

        mockMvc.perform(get("/api/orders/{id}", order.id())
                        .header(HttpHeaders.AUTHORIZATION, bearer("customer2", Role.CUSTOMER)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("본인 주문 또는 본인 매장에 들어온 주문만 조회할 수 있습니다."));
    }

    @Test
    void 다른_매장의_OWNER가_조회하면_403() throws Exception {
        OrderResponse order = order("customer1");

        mockMvc.perform(get("/api/orders/{id}", order.id())
                        .header(HttpHeaders.AUTHORIZATION, bearer("owner2", Role.OWNER)))
                .andExpect(status().isForbidden());
    }

    @Test
    void 없는_주문을_조회하면_404() throws Exception {
        mockMvc.perform(get("/api/orders/{id}", Long.MAX_VALUE)
                        .header(HttpHeaders.AUTHORIZATION, bearer("customer1", Role.CUSTOMER)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("주문이 없습니다."));
    }

    @Test
    void ADMIN은_Security에서_403으로_막힌다() throws Exception {
        OrderResponse order = order("customer1");

        mockMvc.perform(get("/api/orders/{id}", order.id())
                        .header(HttpHeaders.AUTHORIZATION, bearer("admin", Role.ADMIN)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("권한이 없습니다."));
    }

    @Test
    void 토큰의_역할이_아니라_DB의_역할로_판단한다() throws Exception {
        // 토큰은 CUSTOMER로 발급됐지만 DB에서는 OWNER(owner2) → OWNER 기준으로 소유권 확인 → 다른 매장이라 403
        OrderResponse order = order("customer1");

        mockMvc.perform(get("/api/orders/{id}", order.id())
                        .header(HttpHeaders.AUTHORIZATION, bearer("owner2", Role.CUSTOMER)))
                .andExpect(status().isForbidden());
    }

    @Test
    void 항목_수와_관계없이_쿼리_수가_일정하다() {
        OrderResponse twoItems = order("customer1");
        OrderResponse oneItem = orderService.createOrder(new OrderCreateRequest(
                List.of(new OrderItemRequest(chicken.id(), 1)), "06236", "서울 강남구 테헤란로 152", "101호"), "customer1");
        paymentService.pay(new PaymentRequest(twoItems.id(), "CARD"), "customer1");

        Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.setStatisticsEnabled(true);
        statistics.clear();
        OrderDetailResponse detail = orderService.getOrder(twoItems.id(), "customer1");
        long manyItemQueries = statistics.getPrepareStatementCount();
        statistics.clear();
        orderService.getOrder(oneItem.id(), "customer1");
        long oneItemQueries = statistics.getPrepareStatementCount();
        statistics.setStatisticsEnabled(false);

        // 회원 1 + 주문·매장·항목(fetch join) 1 + 결제 1
        assertThat(detail.items()).hasSize(2);
        assertThat(detail.payment()).isNotNull();
        assertThat(manyItemQueries).isEqualTo(3);
        assertThat(oneItemQueries).isEqualTo(3);
    }
}
