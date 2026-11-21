package com.sparta.springdeliverymini.service;

import com.jayway.jsonpath.JsonPath;
import com.sparta.springdeliverymini.TestcontainersConfiguration;
import com.sparta.springdeliverymini.entity.Menu;
import com.sparta.springdeliverymini.entity.Role;
import com.sparta.springdeliverymini.entity.Store;
import com.sparta.springdeliverymini.entity.User;
import com.sparta.springdeliverymini.jwt.JwtProvider;
import com.sparta.springdeliverymini.repository.MenuRepository;
import com.sparta.springdeliverymini.repository.OrderRepository;
import com.sparta.springdeliverymini.repository.PaymentRefundRepository;
import com.sparta.springdeliverymini.repository.PaymentRepository;
import com.sparta.springdeliverymini.repository.StoreRepository;
import com.sparta.springdeliverymini.repository.UserRepository;
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
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 배송 주소 분리·주문/결제 오류 코드를 실제 HTTP 요청 + PostgreSQL로 검증 (#77)
// - 주소: 서버 형식 검증(400 INVALID_ADDRESS), V18 이전 주문(한 줄 주소만)의 조회 응답 호환
// - 오류 코드: 중복 결제·취소된 주문 결제·중복 취소·없는 주문이 상태 코드 + code로 일관되게 반환되는지
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class OrderAddressErrorCodeIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private JwtProvider jwtProvider;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private UserRepository userRepository;
    @Autowired private StoreRepository storeRepository;
    @Autowired private MenuRepository menuRepository;
    @Autowired private OrderRepository orderRepository;
    @Autowired private PaymentRepository paymentRepository;
    @Autowired private PaymentRefundRepository paymentRefundRepository;

    private User customer;
    private Store store;
    private Menu menu;

    @BeforeEach
    void setUp() {
        User owner = userRepository.save(new User("owner1", "encoded-password", Role.OWNER));
        customer = userRepository.save(new User("customer1", "encoded-password", Role.CUSTOMER));
        store = storeRepository.save(new Store("치킨집", owner));
        menu = menuRepository.save(new Menu("후라이드 치킨", 18000, "바삭한 치킨", store));
    }

    @AfterEach
    void tearDown() {
        // FK 순서대로 삭제: payments → orders(order_items는 ON DELETE CASCADE) → menus → stores → users
        paymentRefundRepository.deleteAllInBatch(); // 환불 1건(V21)이 payments를 참조
        paymentRepository.deleteAllInBatch();
        orderRepository.deleteAllInBatch();
        menuRepository.deleteAllInBatch();
        storeRepository.deleteAllInBatch();
        userRepository.deleteAllInBatch();
    }

    // ===== 주소 =====

    @Test
    void 나눈_주소로_주문하면_각_필드와_한_줄_주소가_함께_저장되고_응답된다() throws Exception {
        String body = createOrder("06236", "서울 강남구 테헤란로 152", " 101호 ")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.zipCode").value("06236"))
                .andExpect(jsonPath("$.roadAddress").value("서울 강남구 테헤란로 152"))
                .andExpect(jsonPath("$.detailAddress").value("101호"))
                .andExpect(jsonPath("$.deliveryAddress").value("서울 강남구 테헤란로 152 101호"))
                .andReturn().getResponse().getContentAsString();
        long orderId = ((Number) JsonPath.read(body, "$.id")).longValue();

        assertThat(jdbc.queryForObject("SELECT delivery_address FROM orders WHERE id = ?", String.class, orderId))
                .isEqualTo("서울 강남구 테헤란로 152 101호");
    }

    @Test
    void 우편번호_형식이_틀리면_400_INVALID_ADDRESS이고_주문은_저장되지_않는다() throws Exception {
        createOrder("1234", "서울 강남구 테헤란로 152", "101호")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_ADDRESS"))
                .andExpect(jsonPath("$.message").value("우편번호는 숫자 5자리여야 합니다."));

        assertThat(orderRepository.count()).isZero();
    }

    // 예전 클라이언트가 한 줄 주소(deliveryAddress)만 보내면 우편번호 누락으로 거부 — 나눠지지 않은 새 주문이 생기지 않도록
    @Test
    void 한_줄_주소만_보내면_400_INVALID_ADDRESS() throws Exception {
        mockMvc.perform(auth(post("/api/orders"), customer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"items\": [{\"menuId\": " + menu.getId() + ", \"quantity\": 1}], "
                                + "\"deliveryAddress\": \"서울시\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_ADDRESS"));
    }

    // V18 이전 주문은 한 줄 주소만 있음 → 단건·목록 조회가 그대로 되고 나눈 필드는 null
    @Test
    void 기존_주문은_한_줄_주소로_조회되고_나눈_주소는_null이다() throws Exception {
        jdbc.update("INSERT INTO orders (delivery_address, status, total_price, user_id, store_id, created_at, updated_at) "
                + "VALUES ('서울시 예전 주소 3층', 'ORDER_REQUEST', 18000, ?, ?, now(), now())",
                customer.getId(), store.getId());
        Long orderId = jdbc.queryForObject("SELECT max(id) FROM orders", Long.class);

        mockMvc.perform(auth(get("/api/orders/" + orderId), customer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deliveryAddress").value("서울시 예전 주소 3층"))
                .andExpect(jsonPath("$.zipCode").value(nullValue()))
                .andExpect(jsonPath("$.roadAddress").value(nullValue()))
                .andExpect(jsonPath("$.detailAddress").value(nullValue()));
        mockMvc.perform(auth(get("/api/orders"), customer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].deliveryAddress").value("서울시 예전 주소 3층"));
    }

    // ===== 배달 요청사항 (V19) =====

    @Test
    void 배달_요청사항을_보내면_저장되고_조회_응답에_포함된다() throws Exception {
        String body = mockMvc.perform(auth(post("/api/orders"), customer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"items\": [{\"menuId\": " + menu.getId() + ", \"quantity\": 1}], "
                                + "\"zipCode\": \"00000\", \"roadAddress\": \"체험용 주소 (실제 배달되지 않음)\", "
                                + "\"deliveryRequest\": \" 문 앞에 두고 벨 눌러 주세요 \"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.deliveryRequest").value("문 앞에 두고 벨 눌러 주세요"))
                .andReturn().getResponse().getContentAsString();
        long orderId = ((Number) JsonPath.read(body, "$.id")).longValue();

        mockMvc.perform(auth(get("/api/orders/" + orderId), customer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deliveryRequest").value("문 앞에 두고 벨 눌러 주세요"));
        assertThat(jdbc.queryForObject("SELECT delivery_request FROM orders WHERE id = ?", String.class, orderId))
                .isEqualTo("문 앞에 두고 벨 눌러 주세요");
    }

    @Test
    void 배달_요청사항이_100자를_넘으면_400_INVALID_DELIVERY_REQUEST이고_주문은_저장되지_않는다() throws Exception {
        mockMvc.perform(auth(post("/api/orders"), customer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"items\": [{\"menuId\": " + menu.getId() + ", \"quantity\": 1}], "
                                + "\"zipCode\": \"06236\", \"roadAddress\": \"서울 강남구 테헤란로 152\", "
                                + "\"deliveryRequest\": \"" + "가".repeat(101) + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_DELIVERY_REQUEST"));

        assertThat(orderRepository.count()).isZero();
    }

    // ===== 오류 코드 =====

    @Test
    void 같은_주문을_두_번_결제하면_두_번째는_409_ORDER_ALREADY_PAID() throws Exception {
        long orderId = newOrderId();
        pay(orderId).andExpect(status().isCreated());

        pay(orderId)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ORDER_ALREADY_PAID"));
        assertThat(paymentRepository.count()).isEqualTo(1);
    }

    @Test
    void 취소된_주문을_결제하거나_다시_취소하면_409_ORDER_ALREADY_CANCELLED() throws Exception {
        long orderId = newOrderId();
        cancel(orderId).andExpect(status().is2xxSuccessful());

        pay(orderId)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ORDER_ALREADY_CANCELLED"));
        cancel(orderId)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ORDER_ALREADY_CANCELLED"));
    }

    @Test
    void 없는_주문을_결제하면_404_ORDER_NOT_FOUND() throws Exception {
        pay(Long.MAX_VALUE)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ORDER_NOT_FOUND"));
    }

    @Test
    void 카드_외_결제_수단은_400_UNSUPPORTED_PAYMENT_METHOD() throws Exception {
        mockMvc.perform(auth(post("/api/payments"), customer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"orderId\": " + newOrderId() + ", \"paymentMethod\": \"CASH\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("UNSUPPORTED_PAYMENT_METHOD"));
    }

    // ---- 준비 ----

    private long newOrderId() throws Exception {
        String body = createOrder("06236", "서울 강남구 테헤란로 152", "101호")
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(body, "$.id")).longValue();
    }

    private ResultActions createOrder(String zipCode, String roadAddress, String detailAddress) throws Exception {
        return mockMvc.perform(auth(post("/api/orders"), customer)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"items\": [{\"menuId\": " + menu.getId() + ", \"quantity\": 1}], "
                        + "\"zipCode\": \"" + zipCode + "\", \"roadAddress\": \"" + roadAddress + "\", "
                        + "\"detailAddress\": \"" + detailAddress + "\"}"));
    }

    private ResultActions pay(long orderId) throws Exception {
        return mockMvc.perform(auth(post("/api/payments"), customer)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"orderId\": " + orderId + ", \"paymentMethod\": \"CARD\"}"));
    }

    private ResultActions cancel(long orderId) throws Exception {
        return mockMvc.perform(auth(patch("/api/orders/" + orderId + "/cancel"), customer));
    }

    private MockHttpServletRequestBuilder auth(MockHttpServletRequestBuilder request, User user) {
        return request.header(HttpHeaders.AUTHORIZATION,
                "Bearer " + jwtProvider.createToken(user.getUsername(), user.getRole()));
    }
}
