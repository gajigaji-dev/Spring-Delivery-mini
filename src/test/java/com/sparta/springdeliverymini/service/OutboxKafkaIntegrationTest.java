package com.sparta.springdeliverymini.service;

import com.sparta.springdeliverymini.KafkaTestcontainersConfiguration;
import com.sparta.springdeliverymini.TestcontainersConfiguration;
import com.sparta.springdeliverymini.config.OutboxProperties;
import com.sparta.springdeliverymini.dto.*;
import com.sparta.springdeliverymini.entity.*;
import com.sparta.springdeliverymini.event.DeliveryEventMessage;
import com.sparta.springdeliverymini.event.OutboxPublisher;
import com.sparta.springdeliverymini.event.OutboxPublisher.PublishResult;
import com.sparta.springdeliverymini.repository.*;
import com.sparta.springdeliverymini.service.SettlementIntegrationTest.MutableClock;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.KafkaException;
import org.apache.kafka.common.header.Header;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.*;

// Outbox → Kafka → Consumer(알림) 흐름을 실제 Kafka(Testcontainers)로 검증 (#42)
// - 발행 실패 → 재시도 발행, 같은 메시지 중복 수신 시 알림 1번, 처리 실패 재시도 후 DLT, 형식 오류 즉시 DLT
// - 발행은 스케줄러 대신 publishPending()을 직접 호출해 시점을 고정 (test 프로필은 주기 발행 끔)
@SpringBootTest(properties = {
        "spring.kafka.listener.auto-startup=true",
        "spring.kafka.admin.auto-create=true",
        "outbox.consumer.retry-attempts=2",
        "outbox.consumer.retry-interval=100ms"
})
@Import({TestcontainersConfiguration.class, KafkaTestcontainersConfiguration.class,
        SettlementIntegrationTest.MutableClockConfig.class})
class OutboxKafkaIntegrationTest {

    private static final long PRICE = 10000;
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 14, 12, 0);
    private static final Duration WAIT = Duration.ofSeconds(60); // 첫 Consumer 그룹 참여(리밸런싱)까지 포함

    @Autowired private MutableClock clock;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private OutboxProperties properties;
    @Autowired private ConsumerFactory<?, ?> consumerFactory;
    @Autowired private MeterRegistry meterRegistry;

    @Autowired private StoreService storeService;
    @Autowired private MenuService menuService;
    @Autowired private OrderService orderService;
    @Autowired private PaymentService paymentService;
    @Autowired private AdminPaymentService adminPaymentService;
    @Autowired private OutboxPublisher outboxPublisher;
    @MockitoSpyBean private NotificationService notificationService;
    // Boot가 등록한 빈 타입이 KafkaTemplate<?, ?>라 제네릭을 지정하면 spy 대상을 찾지 못함 → raw 타입
    @SuppressWarnings("rawtypes")
    @MockitoSpyBean private KafkaTemplate kafkaTemplate;

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
    private MenuResponse menu;
    private StoreResponse store;

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

    private PaymentResponse pay() {
        OrderResponse order = orderService.createOrder(
                new OrderCreateRequest(List.of(new OrderItemRequest(menu.id(), 1)), "06236", "서울 강남구 테헤란로 152", "101호"), "customer1");
        return paymentService.pay(new PaymentRequest(order.id(), "CARD"), "customer1");
    }

    private List<Notification> notificationsOf(UUID eventId) {
        return notificationRepository.findAll().stream().filter(n -> n.getEventId().equals(eventId)).toList();
    }

    private OutboxEvent onlyEvent() {
        List<OutboxEvent> events = outboxEventRepository.findAll();
        assertThat(events).hasSize(1);
        return events.getFirst();
    }

    private DeliveryEventMessage message(UUID eventId, OutboxEventType type, long orderId) {
        return new DeliveryEventMessage(eventId, type, orderId, null, store.id(), customer.getId(), owner.getId(), PRICE, NOW);
    }

    // Publisher를 거치지 않고 메시지를 직접 보냄 (재발행·외부 오류 메시지 재현)
    @SuppressWarnings("unchecked")
    private void sendRaw(String key, String value) throws Exception {
        kafkaTemplate.send(properties.topic(), key, value).get(10, TimeUnit.SECONDS);
    }

    // DLT에서 key가 같은 메시지를 찾을 때까지 읽음 (매번 새 그룹 → 토픽 처음부터)
    @SuppressWarnings("unchecked")
    private ConsumerRecord<String, String> pollDeadLetter(String key) {
        try (Consumer<String, String> consumer = (Consumer<String, String>)
                consumerFactory.createConsumer("dlt-verifier-" + UUID.randomUUID(), null)) {
            consumer.subscribe(List.of(properties.deadLetterTopic()));
            long deadline = System.nanoTime() + WAIT.toNanos();
            while (System.nanoTime() < deadline) {
                for (ConsumerRecord<String, String> record : consumer.poll(Duration.ofMillis(500))) {
                    if (key.equals(record.key())) {
                        return record;
                    }
                }
            }
        }
        throw new AssertionError("DLT에서 메시지를 찾지 못함 key=" + key);
    }

    private static String header(ConsumerRecord<String, String> record, String name) {
        Header header = record.headers().lastHeader(name);
        return header == null ? "" : new String(header.value(), StandardCharsets.UTF_8);
    }

    // ===== 발행 → 수신 =====

    @Test
    void 결제_배달완료_환불_이벤트가_Kafka로_발행되고_Consumer가_알림을_저장한다() {
        PaymentResponse payment = pay();
        orderService.statusOrder(payment.orderId(), "owner1", OrderStatus.ORDER_ACCEPTED);
        orderService.statusOrder(payment.orderId(), "owner1", OrderStatus.DELIVERY_COMPLETED);
        adminPaymentService.refund(payment.id(), "admin");

        // 같은 주문의 이벤트는 앞 이벤트가 발행된 뒤에 다음 이벤트가 발행 대상이 됨 (V14 순서 보장) → 주기마다 1건씩
        for (int i = 0; i < 3; i++) {
            assertThat(outboxPublisher.publishPending()).isEqualTo(new PublishResult(1, 0, 0));
        }
        assertThat(outboxPublisher.publishPending()).isEqualTo(new PublishResult(0, 0, 0));

        assertThat(outboxEventRepository.findAll()).allSatisfy(event -> {
            assertThat(event.getStatus()).isEqualTo(OutboxEventStatus.PUBLISHED);
            assertThat(event.getPublishedAt()).isEqualTo(NOW);
        });
        // 결제 2(고객·사장님) + 배달완료 1(고객) + 환불 2(고객·사장님)
        await().atMost(WAIT).untilAsserted(() -> assertThat(notificationRepository.count()).isEqualTo(5));
        assertThat(notificationRepository.findAll())
                .extracting(Notification::getEventType, Notification::getRecipientUserId)
                .containsExactlyInAnyOrder(
                        tuple(OutboxEventType.PAYMENT_COMPLETED, customer.getId()),
                        tuple(OutboxEventType.PAYMENT_COMPLETED, owner.getId()),
                        tuple(OutboxEventType.DELIVERY_COMPLETED, customer.getId()),
                        tuple(OutboxEventType.PAYMENT_REFUNDED, customer.getId()),
                        tuple(OutboxEventType.PAYMENT_REFUNDED, owner.getId()));
        assertThat(notificationRepository.findAll()).allSatisfy(n -> assertThat(n.getOrderId()).isEqualTo(payment.orderId()));
        assertThat(consumedEventRepository.count()).isEqualTo(3);
    }

    @Test
    void Kafka_발행이_실패하면_PENDING으로_남았다가_재시도에서_발행되어_알림이_저장된다() {
        // Kafka 응답 실패 재현 — 결제 트랜잭션은 Kafka를 거치지 않으므로 영향 없음
        doReturn(CompletableFuture.failedFuture(new KafkaException("broker unavailable")))
                .when(kafkaTemplate).send(any(ProducerRecord.class));
        pay();

        assertThat(outboxPublisher.publishPending()).isEqualTo(new PublishResult(0, 1, 0));
        OutboxEvent failed = onlyEvent();
        assertThat(failed.getStatus()).isEqualTo(OutboxEventStatus.PENDING);
        assertThat(failed.getAttemptCount()).isEqualTo(1);
        assertThat(failed.getLastError()).contains("broker unavailable");

        // Kafka 회복 + 재시도 시각 도달 → 발행
        doCallRealMethod().when(kafkaTemplate).send(any(ProducerRecord.class));
        clock.set(NOW.plusSeconds(1));
        assertThat(outboxPublisher.publishPending()).isEqualTo(new PublishResult(1, 0, 0));

        OutboxEvent published = onlyEvent();
        assertThat(published.getStatus()).isEqualTo(OutboxEventStatus.PUBLISHED);
        assertThat(published.getAttemptCount()).isEqualTo(1); // 실패 기록은 남음
        await().atMost(WAIT).untilAsserted(() -> assertThat(notificationsOf(published.getEventId())).hasSize(2));
    }

    // ===== 중복 수신 =====

    @Test
    void 같은_이벤트가_여러_번_전달돼도_알림은_한_번만_저장된다() throws Exception {
        PaymentResponse payment = pay();
        outboxPublisher.publishPending();
        OutboxEvent event = onlyEvent();
        await().atMost(WAIT).untilAsserted(() -> assertThat(notificationsOf(event.getEventId())).hasSize(2));

        // 재발행 재현: 같은 eventId 메시지를 두 번 더 보냄 (발행 성공 후 기록 전 장애 등)
        String key = String.valueOf(payment.orderId());
        sendRaw(key, event.getPayload());
        sendRaw(key, event.getPayload());
        // 같은 키 → 같은 파티션에서 순서대로 처리되므로, 뒤에 보낸 표시 메시지가 처리되면 중복 메시지도 처리 끝난 것
        UUID markerId = UUID.randomUUID();
        sendRaw(key, objectMapper.writeValueAsString(
                message(markerId, OutboxEventType.DELIVERY_COMPLETED, payment.orderId())));
        await().atMost(WAIT).untilAsserted(() -> assertThat(notificationsOf(markerId)).hasSize(1));

        assertThat(notificationsOf(event.getEventId())).hasSize(2);
        assertThat(consumedEventRepository.count()).isEqualTo(2); // 원래 이벤트 + 표시 메시지
        verify(notificationService, times(3)).handle(argThat(m -> m.eventId().equals(event.getEventId())));
    }

    // ===== DLT =====

    @Test
    void 처리가_계속_실패하면_재시도_후_DLT로_보내고_다음_메시지는_계속_처리한다() throws Exception {
        UUID failingId = UUID.randomUUID();
        doThrow(new DataAccessResourceFailureException("DB 장애"))
                .when(notificationService).handle(argThat(m -> m != null && failingId.equals(m.eventId())));
        String key = "dlt-retry-" + failingId;
        String payload = objectMapper.writeValueAsString(message(failingId, OutboxEventType.PAYMENT_COMPLETED, 1L));
        double deadLettersBefore = meterRegistry.counter("outbox.dlt.records").count();

        sendRaw(key, payload);
        UUID nextId = UUID.randomUUID();
        sendRaw(key, objectMapper.writeValueAsString(message(nextId, OutboxEventType.DELIVERY_COMPLETED, 1L)));

        ConsumerRecord<String, String> dead = pollDeadLetter(key);
        assertThat(dead.value()).isEqualTo(payload); // 원본 그대로 보관
        assertThat(header(dead, KafkaHeaders.DLT_ORIGINAL_TOPIC)).isEqualTo(properties.topic());
        assertThat(header(dead, KafkaHeaders.DLT_EXCEPTION_MESSAGE)).contains("DB 장애");
        // 첫 시도 + retry-attempts(2)번
        verify(notificationService, times(3)).handle(argThat(m -> m != null && failingId.equals(m.eventId())));
        assertThat(consumedEventRepository.existsById(failingId)).isFalse();
        assertThat(notificationsOf(failingId)).isEmpty();
        // DLT 전송 성공 후 1건 집계 (#52 — 전송 실패 시 미집계는 KafkaConfigTest)
        await().atMost(WAIT).untilAsserted(() ->
                assertThat(meterRegistry.counter("outbox.dlt.records").count()).isEqualTo(deadLettersBefore + 1));

        // 실패 메시지가 파티션을 막지 않음 → 뒤 메시지는 정상 처리
        await().atMost(WAIT).untilAsserted(() -> assertThat(notificationsOf(nextId)).hasSize(1));
    }

    @Test
    void 형식이_잘못된_메시지는_재시도_없이_DLT로_보낸다() throws Exception {
        String key = "dlt-invalid-" + UUID.randomUUID();
        sendRaw(key, "{not-json");
        // 필수 값(eventId) 누락
        String missingKey = "dlt-missing-" + UUID.randomUUID();
        sendRaw(missingKey, "{\"eventType\":\"PAYMENT_COMPLETED\",\"orderId\":1,\"amount\":1000}");

        ConsumerRecord<String, String> invalid = pollDeadLetter(key);
        assertThat(invalid.value()).isEqualTo("{not-json");
        assertThat(header(invalid, KafkaHeaders.DLT_EXCEPTION_FQCN) + header(invalid, KafkaHeaders.DLT_EXCEPTION_CAUSE_FQCN))
                .contains("InvalidEventMessageException");

        ConsumerRecord<String, String> missing = pollDeadLetter(missingKey);
        assertThat(header(missing, KafkaHeaders.DLT_EXCEPTION_MESSAGE)).contains("필수 값");
        verify(notificationService, never()).handle(any()); // 해석 단계에서 걸러져 처리·재시도 없음
    }
}
