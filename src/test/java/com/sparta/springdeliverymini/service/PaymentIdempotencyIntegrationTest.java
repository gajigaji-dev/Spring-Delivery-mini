package com.sparta.springdeliverymini.service;

import com.sparta.springdeliverymini.TestcontainersConfiguration;
import com.sparta.springdeliverymini.dto.PaymentRequest;
import com.sparta.springdeliverymini.entity.*;
import com.sparta.springdeliverymini.exception.ApiException;
import com.sparta.springdeliverymini.jwt.JwtProvider;
import com.sparta.springdeliverymini.repository.MenuRepository;
import com.sparta.springdeliverymini.repository.OrderRepository;
import com.sparta.springdeliverymini.repository.PaymentRepository;
import com.sparta.springdeliverymini.repository.StoreRepository;
import com.sparta.springdeliverymini.repository.UserRepository;
import com.sparta.springdeliverymini.service.PaymentIdempotencyService.PaymentResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 결제 멱등성(Idempotency-Key)을 실제 PostgreSQL + Redis로 검증 (#41)
// Redis 장애·타임아웃은 PaymentIdempotencyRedisFailureTest
//
// @Transactional을 붙이지 않은 이유: 결제는 커밋된 뒤에 Redis 완료 기록을 남기고, 동시 요청은 각자 별도 트랜잭션
// → 데이터를 실제로 커밋하고 @AfterEach에서 FK 순서대로 정리
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class PaymentIdempotencyIntegrationTest {

    private static final int THREAD_COUNT = 10;

    @Autowired private MockMvc mockMvc;
    @Autowired private JwtProvider jwtProvider;
    @Autowired private PaymentIdempotencyService paymentIdempotencyService;
    @Autowired private StringRedisTemplate redisTemplate;
    @Autowired private UserRepository userRepository;
    @Autowired private StoreRepository storeRepository;
    @Autowired private MenuRepository menuRepository;
    @Autowired private OrderRepository orderRepository;
    @Autowired private PaymentRepository paymentRepository;

    private Store store;
    private Menu menu;

    @AfterEach
    void tearDown() {
        // FK 순서대로 삭제: payments → orders(order_items는 ON DELETE CASCADE로 함께 삭제) → menus → stores → users
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

    @Test
    void 같은_키로_다시_요청하면_결제는_1건이고_처음과_같은_201_응답을_재전송한다() throws Exception {
        User customer = customer("customer1");
        Order order = order(customer);

        String first = pay(customer, order.getId(), "key-1")
                .andExpect(status().isCreated())
                .andExpect(header().doesNotExist("Idempotent-Replayed"))
                .andReturn().getResponse().getContentAsString();

        String second = pay(customer, order.getId(), "key-1")
                .andExpect(status().isCreated())
                .andExpect(header().string("Idempotent-Replayed", "true"))
                .andReturn().getResponse().getContentAsString();

        assertThat(second).isEqualTo(first);
        assertThat(paymentRepository.count()).isEqualTo(1);
    }

    @Test
    void 같은_키로_동시에_요청하면_결제는_1건만_처리되고_나머지는_재전송_또는_처리중_409() throws Exception {
        User customer = customer("customer1");
        Order order = order(customer);
        PaymentRequest request = new PaymentRequest(order.getId(), PaymentMethod.CARD.name());

        ExecutorService executor = Executors.newFixedThreadPool(THREAD_COUNT);
        CountDownLatch ready = new CountDownLatch(THREAD_COUNT);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<PaymentResult>> futures = new ArrayList<>();
        for (int i = 0; i < THREAD_COUNT; i++) {
            futures.add(executor.submit(() -> {
                ready.countDown();
                start.await();
                return paymentIdempotencyService.pay(request, customer.getUsername(), "same-key");
            }));
        }
        ready.await();
        start.countDown();

        List<PaymentResult> results = new ArrayList<>();
        List<Throwable> failures = new ArrayList<>();
        for (Future<PaymentResult> future : futures) {
            try {
                results.add(future.get(30, TimeUnit.SECONDS));
            } catch (ExecutionException e) {
                failures.add(e.getCause());
            }
        }
        executor.shutdown();

        // 실제로 결제를 실행한 요청은 1건, 나머지는 그 결제의 재전송이거나 "처리 중" 409
        // (DB의 이미 결제됨 409까지 가는 요청이 없어야 함 → Redis에서 걸러짐)
        assertThat(results.stream().filter(r -> !r.replayed())).hasSize(1);
        Long paymentId = results.stream().filter(r -> !r.replayed()).findFirst().orElseThrow().response().id();
        assertThat(results).allSatisfy(r -> assertThat(r.response().id()).isEqualTo(paymentId));
        assertThat(failures).allSatisfy(e -> {
            assertThat(e).isInstanceOf(ApiException.class);
            assertThat(((ApiException) e).getStatus()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(e.getMessage()).contains("처리 중");
        });
        assertThat(results.size() + failures.size()).isEqualTo(THREAD_COUNT);
        assertThat(paymentRepository.count()).isEqualTo(1);

        // 처리가 끝난 뒤의 재시도는 항상 처음 응답
        PaymentResult retry = paymentIdempotencyService.pay(request, customer.getUsername(), "same-key");
        assertThat(retry.replayed()).isTrue();
        assertThat(retry.response().id()).isEqualTo(paymentId);
    }

    @Test
    void 같은_키로_다른_주문을_결제하면_422이고_두번째_주문은_결제되지_않는다() throws Exception {
        User customer = customer("customer1");
        Order first = order(customer);
        Order second = order(customer);

        pay(customer, first.getId(), "key-1").andExpect(status().isCreated());
        pay(customer, second.getId(), "key-1")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.message").value("같은 Idempotency-Key로 다른 결제 요청을 보낼 수 없습니다."));

        assertThat(paymentRepository.count()).isEqualTo(1);
        assertThat(orderRepository.findById(second.getId()).orElseThrow().getStatus())
                .isEqualTo(OrderStatus.ORDER_REQUEST);
    }

    @Test
    void 키_없이_다시_요청하면_기존처럼_409() throws Exception {
        User customer = customer("customer1");
        Order order = order(customer);

        pay(customer, order.getId(), null).andExpect(status().isCreated());
        pay(customer, order.getId(), null).andExpect(status().isConflict());

        assertThat(paymentRepository.count()).isEqualTo(1);
        assertThat(redisTemplate.keys(PaymentIdempotencyService.KEY_PREFIX + "*")).isEmpty(); // Redis를 쓰지 않음
    }

    @Test
    void 이미_결제된_주문을_다른_키로_결제하면_DB_검사로_409() throws Exception {
        User customer = customer("customer1");
        Order order = order(customer);

        pay(customer, order.getId(), "key-1").andExpect(status().isCreated());
        pay(customer, order.getId(), "key-2").andExpect(status().isConflict());

        assertThat(paymentRepository.count()).isEqualTo(1);
    }

    @Test
    void 결제가_실패하면_기록을_지워_같은_키로_다시_시도할_수_있다() throws Exception {
        User customer = customer("customer1");
        Order order = order(customer);
        order.cancel();
        orderRepository.save(order);

        // 두 번 모두 실제 결제 검사 결과(취소된 주문 409) — 처리 중 409나 실패 응답 재전송이 아님
        for (int i = 0; i < 2; i++) {
            pay(customer, order.getId(), "key-1")
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.message").value("주문요청 상태의 주문만 결제할 수 있습니다."));
        }
        assertThat(redisTemplate.keys(PaymentIdempotencyService.KEY_PREFIX + "*")).isEmpty();
        assertThat(paymentRepository.count()).isZero();
    }

    @Test
    void 다른_사용자가_같은_키를_써도_서로_영향이_없다() throws Exception {
        User alice = customer("alice");
        User bob = customer("bob");

        pay(alice, order(alice).getId(), "shared-key").andExpect(status().isCreated());
        pay(bob, order(bob).getId(), "shared-key")
                .andExpect(status().isCreated())
                .andExpect(header().doesNotExist("Idempotent-Replayed"));

        assertThat(paymentRepository.count()).isEqualTo(2);
    }

    @Test
    void 잘못된_형식의_키는_400이고_결제하지_않는다() throws Exception {
        User customer = customer("customer1");
        Order order = order(customer);

        pay(customer, order.getId(), "한글 키").andExpect(status().isBadRequest());
        pay(customer, order.getId(), "k".repeat(65)).andExpect(status().isBadRequest());

        assertThat(paymentRepository.count()).isZero();
    }

    @Test
    void 완료_기록은_해시된_키에_완료_TTL로_저장된다() throws Exception {
        User customer = customer("customer1");
        Order order = order(customer);

        pay(customer, order.getId(), "key-1").andExpect(status().isCreated());

        Set<String> keys = redisTemplate.keys(PaymentIdempotencyService.KEY_PREFIX + "*");
        assertThat(keys).containsExactly(PaymentIdempotencyService.redisKey("customer1", "key-1"));
        String key = keys.iterator().next();
        assertThat(key).doesNotContain("customer1").matches("idempotency:payment:[0-9a-f]{64}");
        // completed-ttl 24h (처리 중 TTL 1m보다 길게 바뀌었는지)
        Long ttlSeconds = redisTemplate.getExpire(key);
        assertThat(ttlSeconds).isBetween(Duration.ofHours(23).toSeconds(), Duration.ofHours(24).toSeconds());
        assertThat(redisTemplate.opsForValue().get(key)).contains("\"state\":\"COMPLETED\"");
    }

    // ---- 준비 ----

    private User customer(String username) {
        return userRepository.save(new User(username, "encoded-password", Role.CUSTOMER));
    }

    private Order order(User customer) {
        if (store == null) {
            User owner = userRepository.save(new User("owner1", "encoded-password", Role.OWNER));
            store = storeRepository.save(new Store("치킨집", owner));
            menu = menuRepository.save(new Menu("후라이드 치킨", 18000, "바삭한 치킨", store));
        }
        Order newOrder = new Order(customer, store, "서울시", OrderStatus.ORDER_REQUEST);
        newOrder.addItem(menu, 2);
        return orderRepository.save(newOrder);
    }

    private ResultActions pay(User customer, Long orderId, String idempotencyKey) throws Exception {
        var request = post("/api/payments")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + jwtProvider.createToken(customer.getUsername(), Role.CUSTOMER))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"orderId\": " + orderId + ", \"paymentMethod\": \"CARD\"}");
        if (idempotencyKey != null) {
            request.header("Idempotency-Key", idempotencyKey);
        }
        return mockMvc.perform(request);
    }
}
