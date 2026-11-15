package com.sparta.springdeliverymini.service;

import com.sparta.springdeliverymini.TestcontainersConfiguration;
import com.sparta.springdeliverymini.dto.*;
import com.sparta.springdeliverymini.entity.*;
import com.sparta.springdeliverymini.event.DeliveryEventMessage;
import com.sparta.springdeliverymini.event.OutboxEventRecorder;
import com.sparta.springdeliverymini.event.OutboxPublisher;
import com.sparta.springdeliverymini.event.OutboxPublisher.PublishResult;
import com.sparta.springdeliverymini.repository.*;
import com.sparta.springdeliverymini.service.SettlementIntegrationTest.MutableClock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.ServerSocket;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// Transactional Outbox — Kafka 없이(접속 불가) 실제 PostgreSQL로 검증 (#42)
// - 업무 데이터와 이벤트가 같은 트랜잭션: 함께 커밋, 함께 롤백
// - Kafka 장애여도 결제는 성공하고 이벤트는 재시도 대기 → 백오프 → 한도 초과 FAILED
// - 여러 인스턴스가 동시에 발행해도 같은 이벤트를 한 번만 시도 (SKIP LOCKED)
// - Consumer 멱등성: 같은 이벤트를 동시에·반복 처리해도 알림은 한 번만
// Kafka가 실제로 오가는 흐름(발행·수신·DLT)은 OutboxKafkaIntegrationTest
@SpringBootTest(properties = {
        // 접속할 수 없는 Kafka에 메타데이터를 기다리는 시간 — 기본(5초, application.yml)보다 짧게 해 테스트 시간을 줄임
        "spring.kafka.producer.properties.max.block.ms=300",
        "outbox.publisher.max-attempts=3"
})
@Import({TestcontainersConfiguration.class, SettlementIntegrationTest.MutableClockConfig.class})
class OutboxIntegrationTest {

    private static final long PRICE = 10000;
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 14, 12, 0);

    // 아무것도 듣고 있지 않은 포트 → Kafka 장애(연결 불가) 상태
    @DynamicPropertySource
    static void unreachableKafka(DynamicPropertyRegistry registry) {
        registry.add("spring.kafka.bootstrap-servers", () -> "localhost:" + closedPort());
    }

    private static int closedPort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @Autowired private MutableClock clock;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ObjectMapper objectMapper;

    @Autowired private StoreService storeService;
    @Autowired private MenuService menuService;
    @Autowired private OrderService orderService;
    @Autowired private PaymentService paymentService;
    @Autowired private AdminPaymentService adminPaymentService;
    @Autowired private OutboxPublisher outboxPublisher;
    @Autowired private NotificationService notificationService;
    @Autowired private OutboxEventRecorder outboxEventRecorder;
    @Autowired private TransactionTemplate transactionTemplate;

    @Autowired private UserRepository userRepository;
    @Autowired private StoreRepository storeRepository;
    @Autowired private MenuRepository menuRepository;
    @Autowired private OrderRepository orderRepository;
    @Autowired private PaymentRepository paymentRepository;
    @Autowired private OutboxEventRepository outboxEventRepository;
    @Autowired private ConsumedEventRepository consumedEventRepository;
    @Autowired private NotificationRepository notificationRepository;

    private User customer;
    private User owner;
    private StoreResponse store;
    private MenuResponse menu;

    @BeforeEach
    void setUp() {
        clock.set(NOW);
        userRepository.save(new User("admin", "encoded-password", Role.ADMIN));
        owner = userRepository.save(new User("owner1", "encoded-password", Role.OWNER));
        customer = userRepository.save(new User("customer1", "encoded-password", Role.CUSTOMER));
        store = storeService.createStore(new StoreCreateRequest("치킨집"), "owner1");
        menu = menuService.createMenu(new MenuCreateRequest(store.id(), "치킨", PRICE, null), "owner1");
    }

    @AfterEach
    void tearDown() {
        // 이벤트·알림 테이블은 FK가 없어 순서 무관 → 그 뒤 FK 순서대로 payments → orders → menus → stores → users
        notificationRepository.deleteAllInBatch();
        consumedEventRepository.deleteAllInBatch();
        outboxEventRepository.deleteAllInBatch();
        paymentRepository.deleteAllInBatch();
        orderRepository.deleteAllInBatch();
        menuRepository.deleteAllInBatch();
        storeRepository.deleteAllInBatch();
        userRepository.deleteAllInBatch();
    }

    // ===== 도우미 =====

    private OrderResponse order() {
        return orderService.createOrder(
                new OrderCreateRequest(List.of(new OrderItemRequest(menu.id(), 1)), "서울시"), "customer1");
    }

    private PaymentResponse pay() {
        return paymentService.pay(new PaymentRequest(order().id(), "CARD"), "customer1");
    }

    private List<OutboxEvent> events() {
        return outboxEventRepository.findAll().stream().sorted(Comparator.comparing(OutboxEvent::getId)).toList();
    }

    private OutboxEvent onlyEvent() {
        List<OutboxEvent> events = events();
        assertThat(events).hasSize(1);
        return events.getFirst();
    }

    private DeliveryEventMessage payloadOf(OutboxEvent event) {
        return objectMapper.readValue(event.getPayload(), DeliveryEventMessage.class);
    }

    private DeliveryEventMessage message(OutboxEventType type) {
        return new DeliveryEventMessage(UUID.randomUUID(), type, 1L, 2L, store.id(),
                customer.getId(), owner.getId(), PRICE, NOW);
    }

    // ===== 원자성 =====

    @Test
    void 결제_배달완료_환불이_커밋될_때마다_같은_트랜잭션에서_Outbox_이벤트가_저장된다() {
        PaymentResponse payment = pay();
        orderService.statusOrder(payment.orderId(), "owner1", OrderStatus.ORDER_ACCEPTED);
        clock.set(NOW.plusHours(1));
        orderService.statusOrder(payment.orderId(), "owner1", OrderStatus.DELIVERY_COMPLETED);
        clock.set(NOW.plusDays(1));
        adminPaymentService.refund(payment.id(), "admin");

        List<OutboxEvent> events = events();
        assertThat(events).extracting(OutboxEvent::getEventType).containsExactly(
                OutboxEventType.PAYMENT_COMPLETED, OutboxEventType.DELIVERY_COMPLETED, OutboxEventType.PAYMENT_REFUNDED);
        assertThat(events).allSatisfy(event -> {
            assertThat(event.getStatus()).isEqualTo(OutboxEventStatus.PENDING);
            assertThat(event.getAggregateType()).isEqualTo("ORDER");
            assertThat(event.getAggregateId()).isEqualTo(payment.orderId());
            assertThat(event.getAttemptCount()).isZero();
        });
        assertThat(events).extracting(OutboxEvent::getCreatedAt).containsExactly(NOW, NOW.plusHours(1), NOW.plusDays(1));

        // payload = 발생 시점 스냅샷 (Consumer가 DB 조회 없이 처리할 수 있는 값)
        DeliveryEventMessage paid = payloadOf(events.get(0));
        assertThat(paid.eventId()).isEqualTo(events.get(0).getEventId());
        assertThat(paid.orderId()).isEqualTo(payment.orderId());
        assertThat(paid.paymentId()).isEqualTo(payment.id());
        assertThat(paid.storeId()).isEqualTo(store.id());
        assertThat(paid.customerId()).isEqualTo(customer.getId());
        assertThat(paid.ownerId()).isEqualTo(owner.getId());
        assertThat(paid.amount()).isEqualTo(PRICE);
        assertThat(paid.occurredAt()).isEqualTo(NOW);

        DeliveryEventMessage delivered = payloadOf(events.get(1));
        assertThat(delivered.paymentId()).isNull();
        assertThat(delivered.amount()).isEqualTo(PRICE); // 주문 총액

        DeliveryEventMessage refunded = payloadOf(events.get(2));
        assertThat(refunded.paymentId()).isEqualTo(payment.id());
        assertThat(refunded.occurredAt()).isEqualTo(NOW.plusDays(1));
    }

    @Test
    void 이벤트를_저장한_뒤_트랜잭션이_실패하면_결제와_이벤트가_함께_롤백된다() {
        // 결제(이벤트 INSERT 포함)를 바깥 트랜잭션 안에서 실행한 뒤 실패 → 결제·주문 상태·이벤트 모두 남지 않아야 함
        // (이벤트가 별도 트랜잭션으로 저장됐다면 이벤트만 남음)
        OrderResponse order = order();

        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(status -> {
            paymentService.pay(new PaymentRequest(order.id(), "CARD"), "customer1");
            assertThat(outboxEventRepository.count()).isEqualTo(1); // 같은 트랜잭션 안에서는 보임
            throw new IllegalStateException("이벤트 저장 직후 실패");
        })).hasMessage("이벤트 저장 직후 실패");

        assertThat(outboxEventRepository.count()).isZero();
        assertThat(paymentRepository.count()).isZero();
        assertThat(orderRepository.findById(order.id()).orElseThrow().getStatus()).isEqualTo(OrderStatus.ORDER_REQUEST);
    }

    @Test
    void 실패한_업무_요청은_이벤트를_남기지_않는다() {
        PaymentResponse payment = pay();

        // 중복 결제 409, 배달 전 환불 409 → 이벤트는 처음 결제 1건뿐
        assertThatThrownBy(() -> paymentService.pay(new PaymentRequest(payment.orderId(), "CARD"), "customer1"))
                .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> adminPaymentService.refund(payment.id(), "admin"))
                .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> orderService.statusOrder(payment.orderId(), "owner1", OrderStatus.DELIVERY_COMPLETED))
                .isInstanceOf(RuntimeException.class);

        assertThat(onlyEvent().getEventType()).isEqualTo(OutboxEventType.PAYMENT_COMPLETED);
    }

    @Test
    void 업무_트랜잭션_밖에서는_이벤트를_저장할_수_없다() {
        PaymentResponse response = pay();
        Payment payment = paymentRepository.findById(response.id()).orElseThrow();

        assertThatThrownBy(() -> outboxEventRecorder.paymentCompleted(payment))
                .isInstanceOf(IllegalTransactionStateException.class);
        assertThat(outboxEventRepository.count()).isEqualTo(1); // 결제 때 저장된 1건만
    }

    // ===== Kafka 장애·재시도 =====

    @Test
    void Kafka에_연결할_수_없어도_결제는_성공하고_이벤트는_재시도_대기로_남는다() {
        PaymentResponse payment = pay(); // Kafka 접속 불가 상태에서도 정상 응답

        PublishResult result = outboxPublisher.publishPending();

        assertThat(result).isEqualTo(new PublishResult(0, 1, 0));
        OutboxEvent event = onlyEvent();
        assertThat(event.getStatus()).isEqualTo(OutboxEventStatus.PENDING);
        assertThat(event.getAttemptCount()).isEqualTo(1);
        assertThat(event.getNextAttemptAt()).isEqualTo(NOW.plusSeconds(1)); // initial-backoff 1초
        assertThat(event.getLastError()).isNotBlank();
        assertThat(event.getPublishedAt()).isNull();
        // 결제는 그대로 커밋되어 있음
        assertThat(paymentRepository.findById(payment.id()).orElseThrow().getStatus()).isEqualTo(PaymentStatus.COMPLETED);
    }

    @Test
    void 재시도_시각_전에는_다시_시도하지_않고_한도에_닿으면_FAILED로_남아_더는_시도하지_않는다() {
        pay();

        assertThat(outboxPublisher.publishPending()).isEqualTo(new PublishResult(0, 1, 0)); // 1번째 실패 → +1초
        assertThat(outboxPublisher.publishPending()).isEqualTo(new PublishResult(0, 0, 0)); // 아직 재시도 시각 전

        clock.set(NOW.plusSeconds(1));
        assertThat(outboxPublisher.publishPending()).isEqualTo(new PublishResult(0, 1, 0)); // 2번째 실패 → +2초
        assertThat(onlyEvent().getNextAttemptAt()).isEqualTo(NOW.plusSeconds(3));

        clock.set(NOW.plusSeconds(3));
        assertThat(outboxPublisher.publishPending()).isEqualTo(new PublishResult(0, 0, 1)); // 3번째 = max-attempts
        OutboxEvent failed = onlyEvent();
        assertThat(failed.getStatus()).isEqualTo(OutboxEventStatus.FAILED);
        assertThat(failed.getAttemptCount()).isEqualTo(3);

        clock.set(NOW.plusDays(1));
        assertThat(outboxPublisher.publishPending()).isEqualTo(new PublishResult(0, 0, 0)); // FAILED는 자동 발행 대상 아님
    }

    @Test
    void 여러_인스턴스가_동시에_발행해도_같은_이벤트를_한_번씩만_시도한다() throws Exception {
        pay();
        pay();
        int instances = 4;
        ExecutorService executor = Executors.newFixedThreadPool(instances);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<PublishResult>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < instances; i++) {
                futures.add(executor.submit(() -> {
                    start.await();
                    return outboxPublisher.publishPending();
                }));
            }
            start.countDown();
            int attempted = 0;
            for (Future<PublishResult> future : futures) {
                PublishResult result = future.get(30, TimeUnit.SECONDS);
                attempted += result.published() + result.retry() + result.failed();
            }
            // 먼저 행을 잡은 쪽만 시도하고 나머지는 SKIP LOCKED로 건너뜀 → 같은 이벤트를 두 인스턴스가 함께 시도하지 않음
            // (Kafka 접속 불가라 행을 잡은 인스턴스는 첫 실패 뒤 나머지를 다음 주기로 미룸 → 시도 건수는 1~2건)
            List<Integer> attempts = events().stream().map(OutboxEvent::getAttemptCount).toList();
            assertThat(attempts).allSatisfy(count -> assertThat(count).isLessThanOrEqualTo(1));
            assertThat(attempted).isPositive().isEqualTo(attempts.stream().mapToInt(Integer::intValue).sum());
        } finally {
            executor.shutdownNow();
        }
    }

    // ===== Consumer 멱등성 =====

    @Test
    void 같은_이벤트를_동시에_여러_번_처리해도_알림은_한_번만_저장된다() throws Exception {
        DeliveryEventMessage message = message(OutboxEventType.PAYMENT_COMPLETED);
        int threads = 8;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < threads; i++) {
                futures.add(executor.submit(() -> {
                    start.await();
                    return notificationService.handle(message);
                }));
            }
            start.countDown();
            int saved = 0;
            for (Future<Boolean> future : futures) {
                if (future.get(30, TimeUnit.SECONDS)) {
                    saved++;
                }
            }
            assertThat(saved).isEqualTo(1);
        } finally {
            executor.shutdownNow();
        }

        assertThat(notificationService.handle(message)).isFalse(); // 나중에 다시 와도 무시
        assertThat(consumedEventRepository.count()).isEqualTo(1);
        List<Notification> notifications = notificationRepository.findAll();
        assertThat(notifications).hasSize(2)
                .extracting(Notification::getRecipientUserId)
                .containsExactlyInAnyOrder(customer.getId(), owner.getId());
        assertThat(notifications).allSatisfy(n -> assertThat(n.getEventId()).isEqualTo(message.eventId()));
    }

    @Test
    void 이벤트_종류별로_받는_사람과_문구가_정해진다() {
        DeliveryEventMessage paid = message(OutboxEventType.PAYMENT_COMPLETED);
        DeliveryEventMessage refunded = message(OutboxEventType.PAYMENT_REFUNDED);
        DeliveryEventMessage delivered = message(OutboxEventType.DELIVERY_COMPLETED);

        notificationService.handle(paid);
        notificationService.handle(refunded);
        notificationService.handle(delivered);

        assertThat(jdbc.queryForList("SELECT recipient_user_id, message FROM notifications WHERE event_id = ? ORDER BY recipient_user_id",
                paid.eventId())).hasSize(2);
        assertThat(jdbc.queryForObject("SELECT message FROM notifications WHERE event_id = ? AND recipient_user_id = ?",
                String.class, refunded.eventId(), owner.getId()))
                .isEqualTo("주문 #1 결제 10,000원이 환불되어 다음 정산에서 차감됩니다.");
        assertThat(jdbc.queryForList("SELECT recipient_user_id FROM notifications WHERE event_id = ?",
                Long.class, delivered.eventId())).containsExactly(customer.getId()); // 배달완료는 고객에게만
        assertThat(jdbc.queryForObject("SELECT message FROM notifications WHERE event_id = ? AND recipient_user_id = ?",
                String.class, paid.eventId(), customer.getId()))
                .isEqualTo("주문 #1 결제가 완료되었습니다. (10,000원)");
    }

    @Test
    void 알림_저장이_실패하면_처리_기록도_롤백되어_다시_처리할_수_있다() {
        // 같은 이벤트·같은 사람의 알림이 이미 있는 상태(UNIQUE 위반)로 알림 저장 실패를 만듦
        DeliveryEventMessage message = message(OutboxEventType.DELIVERY_COMPLETED);
        jdbc.update("INSERT INTO notifications (event_id, event_type, recipient_user_id, order_id, message, created_at, "
                + "send_status, send_attempt_count, next_send_at) "
                + "VALUES (?, 'DELIVERY_COMPLETED', ?, 1, '먼저 저장된 알림', ?, 'PENDING', 0, ?)",
                message.eventId(), customer.getId(), NOW, NOW);

        assertThatThrownBy(() -> notificationService.handle(message)).isInstanceOf(RuntimeException.class);
        assertThat(consumedEventRepository.existsById(message.eventId())).isFalse(); // 처리 기록만 남지 않음

        jdbc.update("DELETE FROM notifications WHERE event_id = ?", message.eventId());
        assertThat(notificationService.handle(message)).isTrue(); // 재시도하면 처리됨
        assertThat(consumedEventRepository.existsById(message.eventId())).isTrue();
    }

    @Test
    void Outbox_테이블_제약이_정의되지_않은_종류와_어긋난_발행_상태를_거부한다() {
        // outbox_events.event_type CHECK — 정의되지 않은 종류는 DB가 거부
        assertThatThrownBy(() -> jdbc.update("INSERT INTO outbox_events (event_id, aggregate_type, aggregate_id, event_type, "
                + "payload, status, attempt_count, next_attempt_at, created_at) VALUES (?, 'ORDER', 1, 'UNKNOWN', '{}', "
                + "'PENDING', 0, ?, ?)", UUID.randomUUID(), NOW, NOW))
                .isInstanceOf(RuntimeException.class);
        // PUBLISHED ⇔ published_at 있음
        assertThatThrownBy(() -> jdbc.update("INSERT INTO outbox_events (event_id, aggregate_type, aggregate_id, event_type, "
                + "payload, status, attempt_count, next_attempt_at, created_at) VALUES (?, 'ORDER', 1, 'PAYMENT_COMPLETED', '{}', "
                + "'PUBLISHED', 0, ?, ?)", UUID.randomUUID(), NOW, NOW))
                .isInstanceOf(RuntimeException.class);
    }
}
