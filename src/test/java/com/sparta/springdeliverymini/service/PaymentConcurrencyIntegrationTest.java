package com.sparta.springdeliverymini.service;

import com.sparta.springdeliverymini.TestcontainersConfiguration;
import com.sparta.springdeliverymini.dto.PaymentRequest;
import com.sparta.springdeliverymini.entity.*;
import com.sparta.springdeliverymini.exception.ApiException;
import com.sparta.springdeliverymini.exception.InvalidOrderStatusException;
import com.sparta.springdeliverymini.repository.MenuRepository;
import com.sparta.springdeliverymini.repository.OrderRepository;
import com.sparta.springdeliverymini.repository.PaymentRepository;
import com.sparta.springdeliverymini.repository.StoreRepository;
import com.sparta.springdeliverymini.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;

// 같은 주문에 결제 요청이 동시에 여러 번 들어와도 결제 내역은 1건만 저장되는지 실제 PostgreSQL로 검증
// (PaymentServiceTest는 Mock이라 unique 제약·트랜잭션 동시성은 검증할 수 없음)
//
// @Transactional을 붙이지 않은 이유: 테스트 트랜잭션 안에서 저장한 데이터는 커밋되지 않아
// 다른 스레드(각자 별도 트랜잭션)에서 보이지 않음 → 데이터를 실제로 커밋하고 @AfterEach에서 정리
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class PaymentConcurrencyIntegrationTest {

    private static final int THREAD_COUNT = 10;

    @Autowired
    private PaymentService paymentService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private StoreRepository storeRepository;

    @Autowired
    private MenuRepository menuRepository;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private PaymentRepository paymentRepository;

    @AfterEach
    void tearDown() {
        // FK 순서대로 삭제: payments → orders(order_items는 ON DELETE CASCADE로 함께 삭제) → menus → stores → users
        paymentRepository.deleteAllInBatch();
        orderRepository.deleteAllInBatch();
        menuRepository.deleteAllInBatch();
        storeRepository.deleteAllInBatch();
        userRepository.deleteAllInBatch();
    }

    @Test
    void 같은_주문을_동시에_결제하면_결제는_1건만_저장되고_나머지는_409() throws Exception {
        // given: customer가 owner의 메뉴를 주문한 상태 (ORDER_REQUEST)
        User owner = userRepository.save(new User("owner1", "encoded-password", Role.OWNER));
        User customer = userRepository.save(new User("customer1", "encoded-password", Role.CUSTOMER));
        Store store = storeRepository.save(new Store("치킨집", owner));
        Menu menu = menuRepository.save(new Menu("후라이드 치킨", 18000, "바삭한 치킨", store));
        Order newOrder = new Order(customer, store, "서울시", OrderStatus.ORDER_REQUEST);
        newOrder.addItem(menu, 2);
        Order order = orderRepository.save(newOrder);
        PaymentRequest request = new PaymentRequest(order.getId(), PaymentMethod.CARD.name());

        // when: THREAD_COUNT개의 스레드가 준비되면 동시에 결제 요청
        ExecutorService executor = Executors.newFixedThreadPool(THREAD_COUNT);
        CountDownLatch ready = new CountDownLatch(THREAD_COUNT);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < THREAD_COUNT; i++) {
            futures.add(executor.submit(() -> {
                ready.countDown();
                start.await();
                return paymentService.pay(request, customer.getUsername());
            }));
        }
        ready.await();
        start.countDown();

        int successCount = 0;
        List<Throwable> failures = new ArrayList<>();
        for (Future<?> future : futures) {
            try {
                future.get(30, TimeUnit.SECONDS);
                successCount++;
            } catch (ExecutionException e) {
                failures.add(e.getCause());
            }
        }
        executor.shutdown();

        // then: 성공 1건, 나머지는 모두 409로 변환되는 예외
        //   - 먼저 결제가 끝난 뒤 들어온 요청 → Order.pay()가 InvalidOrderStatusException
        //   - 동시에 검사를 통과한 요청 → order_id unique 위반 → ApiException(CONFLICT)
        assertThat(successCount).isEqualTo(1);
        assertThat(failures).hasSize(THREAD_COUNT - 1);
        assertThat(failures).allSatisfy(e -> assertThat(
                e instanceof InvalidOrderStatusException
                        || (e instanceof ApiException api && api.getStatus() == HttpStatus.CONFLICT)
        ).as("409로 응답되는 예외여야 함: %s", e).isTrue());

        assertThat(paymentRepository.count()).isEqualTo(1);

        // 수수료 컬럼(V3)이 실제 DB에 저장·조회되는지 (36000원 × 5% = 1800원)
        Payment saved = paymentRepository.findAll().get(0);
        assertThat(saved.getFeeRate()).isEqualByComparingTo("0.05");
        assertThat(saved.getFeeAmount()).isEqualTo(1800);
        assertThat(saved.getSettlementAmount()).isEqualTo(34200);

        assertThat(orderRepository.findById(order.getId()).orElseThrow().getStatus())
                .isEqualTo(OrderStatus.PAYMENT_COMPLETED);
    }
}
