package com.sparta.springdeliverymini.service;

import com.sparta.springdeliverymini.TestcontainersConfiguration;
import com.sparta.springdeliverymini.dto.*;
import com.sparta.springdeliverymini.entity.*;
import com.sparta.springdeliverymini.event.DeliveryEventMessage;
import com.sparta.springdeliverymini.event.NotificationDispatcher;
import com.sparta.springdeliverymini.event.NotificationDispatcher.DispatchResult;
import com.sparta.springdeliverymini.event.NotificationSender;
import com.sparta.springdeliverymini.exception.ApiException;
import com.sparta.springdeliverymini.exception.InvalidOutboxEventStatusException;
import com.sparta.springdeliverymini.maintenance.RetentionCleaner;
import com.sparta.springdeliverymini.maintenance.RetentionCleaner.CleanupResult;
import com.sparta.springdeliverymini.repository.*;
import com.sparta.springdeliverymini.service.SettlementIntegrationTest.MutableClock;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.data.domain.Slice;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// Redis·Kafka 후속 개선(V14) — 실제 PostgreSQL로 검증, Kafka는 쓰지 않음 (발행 대상 조회·상태 변경만 확인)
// - 발행 순서: 같은 주문의 앞 이벤트가 PENDING이면 뒤 이벤트는 발행 대상이 아님
// - 관리자 재발행: FAILED → PENDING (단건·일괄), 그 밖의 상태는 409
// - 알림 발송: PENDING → SENT / 실패 시 재시도 → 한도 초과 FAILED, SKIPPED는 발송하지 않음
// - 보관 기간 정리: 발행 완료 이벤트·처리 기록·멱등성 기록만 기간이 지난 것을 삭제
// - 내 알림 목록, Outbox 메트릭
@SpringBootTest(properties = {
        "notification.dispatcher.max-attempts=2",
        // batch보다 많은 행을 여러 번에 나눠 지우는지 확인
        "retention.batch-size=2"
})
@Import({TestcontainersConfiguration.class, SettlementIntegrationTest.MutableClockConfig.class,
        OutboxFollowUpIntegrationTest.FailingSenderConfig.class})
class OutboxFollowUpIntegrationTest {

    private static final long PRICE = 10000;
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 14, 12, 0);

    // 지정한 사용자에게 보내는 알림만 실패하는 발송기 (기본은 모두 성공)
    static class FailingSender implements NotificationSender {
        final Set<Long> failingRecipients = ConcurrentHashMap.newKeySet();
        final List<Long> sent = new java.util.concurrent.CopyOnWriteArrayList<>();

        @Override
        public void send(Notification notification) {
            if (failingRecipients.contains(notification.getRecipientUserId())) {
                throw new IllegalStateException("채널 장애");
            }
            sent.add(notification.getId());
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class FailingSenderConfig {
        @Bean
        @Primary
        FailingSender failingSender() {
            return new FailingSender();
        }
    }

    @Autowired private MutableClock clock;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private TransactionTemplate transactionTemplate;
    @Autowired private MeterRegistry meterRegistry;
    @Autowired private FailingSender sender;

    @Autowired private StoreService storeService;
    @Autowired private MenuService menuService;
    @Autowired private OrderService orderService;
    @Autowired private PaymentService paymentService;
    @Autowired private NotificationService notificationService;
    @Autowired private AdminOutboxService adminOutboxService;
    @Autowired private NotificationDispatcher dispatcher;
    @Autowired private RetentionCleaner retentionCleaner;

    @Autowired private UserRepository userRepository;
    @Autowired private StoreRepository storeRepository;
    @Autowired private MenuRepository menuRepository;
    @Autowired private OrderRepository orderRepository;
    @Autowired private PaymentRepository paymentRepository;
    @Autowired private PaymentIdempotencyKeyRepository idempotencyKeyRepository;
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
        sender.failingRecipients.clear();
        sender.sent.clear();
        userRepository.save(new User("admin", "encoded-password", Role.ADMIN));
        owner = userRepository.save(new User("owner1", "encoded-password", Role.OWNER));
        customer = userRepository.save(new User("customer1", "encoded-password", Role.CUSTOMER));
        store = storeService.createStore(new StoreCreateRequest("치킨집"), "owner1");
        menu = menuService.createMenu(new MenuCreateRequest(store.id(), "치킨", PRICE, null), "owner1");
    }

    @AfterEach
    void tearDown() {
        // 이벤트·알림 테이블은 FK가 없어 순서 무관 → 멱등성 기록(payments·users 참조) → payments → orders → menus → stores → users
        notificationRepository.deleteAllInBatch();
        consumedEventRepository.deleteAllInBatch();
        outboxEventRepository.deleteAllInBatch();
        idempotencyKeyRepository.deleteAllInBatch();
        paymentRepository.deleteAllInBatch();
        orderRepository.deleteAllInBatch();
        menuRepository.deleteAllInBatch();
        storeRepository.deleteAllInBatch();
        userRepository.deleteAllInBatch();
    }

    // ===== 도우미 =====

    // 원하는 상태의 Outbox 이벤트를 직접 넣음 (Kafka 없이 FAILED·PUBLISHED 상태를 만들기 위함)
    private long insertEvent(long orderId, String status, LocalDateTime createdAt, LocalDateTime nextAttemptAt) {
        LocalDateTime publishedAt = "PUBLISHED".equals(status) ? createdAt : null;
        int attemptCount = "FAILED".equals(status) ? 10 : 0;
        return jdbc.queryForObject("INSERT INTO outbox_events (event_id, aggregate_type, aggregate_id, event_type, payload, "
                        + "status, attempt_count, next_attempt_at, last_error, created_at, published_at) "
                        + "VALUES (?, 'ORDER', ?, 'PAYMENT_COMPLETED', '{}', ?, ?, ?, ?, ?, ?) RETURNING id",
                Long.class, UUID.randomUUID(), orderId, status, attemptCount, nextAttemptAt,
                "FAILED".equals(status) ? "broker down" : null, createdAt, publishedAt);
    }

    private long insertEvent(long orderId, String status) {
        return insertEvent(orderId, status, NOW, NOW);
    }

    private List<Long> publishableIds() {
        return transactionTemplate.execute(tx ->
                outboxEventRepository.findPublishableForUpdate(LocalDateTime.now(clock), 100).stream()
                        .map(OutboxEvent::getId).toList());
    }

    private String statusOf(long eventId) {
        return jdbc.queryForObject("SELECT status FROM outbox_events WHERE id = ?", String.class, eventId);
    }

    private DeliveryEventMessage message(OutboxEventType type) {
        return new DeliveryEventMessage(UUID.randomUUID(), type, 1L, 2L, store.id(),
                customer.getId(), owner.getId(), PRICE, NOW);
    }

    private Notification notificationOf(User recipient) {
        return notificationRepository.findAll().stream()
                .filter(n -> n.getRecipientUserId().equals(recipient.getId()))
                .findFirst().orElseThrow();
    }

    private double gauge(String name, String status) {
        var search = meterRegistry.get(name);
        return (status == null ? search : search.tag("status", status)).gauge().value();
    }

    // ===== 발행 순서 =====

    @Test
    void 같은_주문의_앞_이벤트가_재시도_대기_중이면_뒤_이벤트는_발행_대상이_아니다() {
        // 주문 1: 앞 이벤트가 실패해 5분 뒤 재시도 예정, 뒤 이벤트는 바로 발행 가능한 상태
        long first = insertEvent(1, "PENDING", NOW, NOW.plusMinutes(5));
        long second = insertEvent(1, "PENDING", NOW, NOW);
        long otherOrder = insertEvent(2, "PENDING", NOW, NOW);

        // 앞 이벤트가 나가기 전에는 뒤 이벤트를 보내지 않음 (다른 주문은 영향 없음)
        assertThat(publishableIds()).containsExactly(otherOrder);

        // 재시도 시각이 되면 앞 이벤트만 발행 대상 (한 batch에 주문당 하나)
        clock.set(NOW.plusMinutes(5));
        assertThat(publishableIds()).containsExactly(first, otherOrder);

        // 앞 이벤트가 발행되면 뒤 이벤트 차례
        jdbc.update("UPDATE outbox_events SET status = 'PUBLISHED', published_at = ? WHERE id = ?", NOW, first);
        assertThat(publishableIds()).containsExactly(second, otherOrder);
    }

    @Test
    void 앞_이벤트가_FAILED면_뒤_이벤트를_막지_않는다() {
        insertEvent(1, "FAILED");
        long second = insertEvent(1, "PENDING");

        assertThat(publishableIds()).containsExactly(second);
    }

    // ===== 관리자 재발행 =====

    @Test
    void FAILED_이벤트를_재발행하면_PENDING으로_돌아가_다시_발행_대상이_된다() {
        long failed = insertEvent(1, "FAILED", NOW.minusHours(1), NOW.minusHours(1));

        OutboxEventResponse response = adminOutboxService.republish(failed, "admin");

        assertThat(response.status()).isEqualTo(OutboxEventStatus.PENDING);
        assertThat(response.attemptCount()).isZero();
        assertThat(response.nextAttemptAt()).isEqualTo(NOW);
        assertThat(response.lastError()).isEqualTo("broker down");
        assertThat(publishableIds()).containsExactly(failed);
    }

    @Test
    void FAILED가_아닌_이벤트는_재발행할_수_없고_없는_이벤트는_404() {
        long pending = insertEvent(1, "PENDING");
        long published = insertEvent(2, "PUBLISHED");

        assertThatThrownBy(() -> adminOutboxService.republish(pending, "admin"))
                .isInstanceOf(InvalidOutboxEventStatusException.class);
        assertThatThrownBy(() -> adminOutboxService.republish(published, "admin"))
                .isInstanceOf(InvalidOutboxEventStatusException.class);
        assertThatThrownBy(() -> adminOutboxService.republish(999_999L, "admin"))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
        assertThat(statusOf(published)).isEqualTo("PUBLISHED");
    }

    @Test
    void 일괄_재발행은_오래된_FAILED부터_limit건만_되돌린다() {
        long first = insertEvent(1, "FAILED");
        long second = insertEvent(2, "FAILED");
        long third = insertEvent(3, "FAILED");
        long published = insertEvent(4, "PUBLISHED");

        OutboxRepublishResponse response = adminOutboxService.republishFailed(2, "admin");

        assertThat(response.republished()).isEqualTo(2);
        assertThat(statusOf(first)).isEqualTo("PENDING");
        assertThat(statusOf(second)).isEqualTo("PENDING");
        assertThat(statusOf(third)).isEqualTo("FAILED");
        assertThat(statusOf(published)).isEqualTo("PUBLISHED");

        assertThatThrownBy(() -> adminOutboxService.republishFailed(0, "admin"))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));
        assertThatThrownBy(() -> adminOutboxService.republishFailed(101, "admin"))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    void 상태별_이벤트_목록을_id_순으로_페이지_조회한다() {
        long first = insertEvent(1, "FAILED");
        insertEvent(2, "PENDING");
        long second = insertEvent(3, "FAILED");
        long third = insertEvent(4, "FAILED");

        Slice<OutboxEventResponse> page0 = adminOutboxService.getEvents(OutboxEventStatus.FAILED, PageQuery.of(0, 2), "admin");
        Slice<OutboxEventResponse> page1 = adminOutboxService.getEvents(OutboxEventStatus.FAILED, PageQuery.of(1, 2), "admin");

        assertThat(page0.getContent()).extracting(OutboxEventResponse::id).containsExactly(first, second);
        assertThat(page0.hasNext()).isTrue();
        assertThat(page1.getContent()).extracting(OutboxEventResponse::id).containsExactly(third);
        assertThat(page1.hasNext()).isFalse();
    }

    @Test
    void 관리자가_아니면_DB_기준으로_403이고_이벤트는_바뀌지_않는다() {
        // 토큰은 ADMIN으로 발급됐지만 그 사이 역할이 바뀐 경우를 Service에서 다시 확인
        long failed = insertEvent(1, "FAILED");

        for (String username : List.of("owner1", "customer1")) {
            assertThatThrownBy(() -> adminOutboxService.republish(failed, username))
                    .isInstanceOfSatisfying(ApiException.class,
                            e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
            assertThatThrownBy(() -> adminOutboxService.republishFailed(10, username))
                    .isInstanceOfSatisfying(ApiException.class,
                            e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
            assertThatThrownBy(() -> adminOutboxService.getEvents(OutboxEventStatus.FAILED, PageQuery.of(0, 20), username))
                    .isInstanceOfSatisfying(ApiException.class,
                            e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
        }
        assertThat(statusOf(failed)).isEqualTo("FAILED");
    }

    // ===== 알림 발송 =====

    @Test
    void 저장된_알림을_발송하면_SENT가_되고_다시_발송하지_않는다() {
        notificationService.handle(message(OutboxEventType.PAYMENT_COMPLETED)); // 고객·사장님 알림 2건
        clock.set(NOW.plusSeconds(5));

        DispatchResult first = dispatcher.dispatchPending();
        DispatchResult second = dispatcher.dispatchPending();

        assertThat(first).isEqualTo(new DispatchResult(2, 0, 0));
        assertThat(second).isEqualTo(new DispatchResult(0, 0, 0));
        assertThat(sender.sent).hasSize(2);
        assertThat(notificationRepository.findAll()).allSatisfy(notification -> {
            assertThat(notification.getSendStatus()).isEqualTo(NotificationSendStatus.SENT);
            assertThat(notification.getSentAt()).isEqualTo(NOW.plusSeconds(5));
            assertThat(notification.getSendAttemptCount()).isZero();
        });
    }

    @Test
    void 발송에_실패한_알림만_재시도로_남고_한도에_닿으면_FAILED로_남는다() {
        notificationService.handle(message(OutboxEventType.PAYMENT_COMPLETED));
        sender.failingRecipients.add(owner.getId());

        // 1번째 시도: 고객 알림은 발송, 사장님 알림만 실패 → 1분 뒤 재시도
        assertThat(dispatcher.dispatchPending()).isEqualTo(new DispatchResult(1, 1, 0));
        assertThat(notificationOf(customer).getSendStatus()).isEqualTo(NotificationSendStatus.SENT);
        Notification retrying = notificationOf(owner);
        assertThat(retrying.getSendStatus()).isEqualTo(NotificationSendStatus.PENDING);
        assertThat(retrying.getSendAttemptCount()).isEqualTo(1);
        assertThat(retrying.getNextSendAt()).isEqualTo(NOW.plusMinutes(1));
        assertThat(retrying.getLastSendError()).contains("채널 장애");

        // 재시도 시각 전에는 시도하지 않음
        clock.set(NOW.plusSeconds(59));
        assertThat(dispatcher.dispatchPending()).isEqualTo(new DispatchResult(0, 0, 0));

        // 2번째 실패 = 한도(max-attempts=2) → FAILED, 이후 채널이 회복돼도 자동 발송하지 않음
        clock.set(NOW.plusMinutes(1));
        assertThat(dispatcher.dispatchPending()).isEqualTo(new DispatchResult(0, 0, 1));
        Notification failed = notificationOf(owner);
        assertThat(failed.getSendStatus()).isEqualTo(NotificationSendStatus.FAILED);
        assertThat(failed.getSendAttemptCount()).isEqualTo(2);
        assertThat(failed.getSentAt()).isNull();

        sender.failingRecipients.clear();
        clock.set(NOW.plusHours(1));
        assertThat(dispatcher.dispatchPending()).isEqualTo(new DispatchResult(0, 0, 0));
    }

    @Test
    void 재시도에서_발송에_성공하면_SENT가_된다() {
        notificationService.handle(message(OutboxEventType.DELIVERY_COMPLETED)); // 고객 알림 1건
        sender.failingRecipients.add(customer.getId());
        assertThat(dispatcher.dispatchPending()).isEqualTo(new DispatchResult(0, 1, 0));

        sender.failingRecipients.clear();
        clock.set(NOW.plusMinutes(1));

        assertThat(dispatcher.dispatchPending()).isEqualTo(new DispatchResult(1, 0, 0));
        Notification sent = notificationOf(customer);
        assertThat(sent.getSendStatus()).isEqualTo(NotificationSendStatus.SENT);
        assertThat(sent.getSendAttemptCount()).isEqualTo(1); // 실패 기록은 남음
    }

    @Test
    void 발송_기능_도입_전의_SKIPPED_알림은_발송하지_않는다() {
        notificationService.handle(message(OutboxEventType.DELIVERY_COMPLETED));
        jdbc.update("UPDATE notifications SET send_status = 'SKIPPED'");

        assertThat(dispatcher.dispatchPending()).isEqualTo(new DispatchResult(0, 0, 0));
        assertThat(sender.sent).isEmpty();
    }

    // ===== 내 알림 목록 =====

    @Test
    void 내_알림_목록은_내가_받는_알림만_id_순으로_페이지_조회한다() {
        notificationService.handle(message(OutboxEventType.PAYMENT_COMPLETED));  // 고객·사장님
        notificationService.handle(message(OutboxEventType.DELIVERY_COMPLETED)); // 고객
        notificationService.handle(message(OutboxEventType.PAYMENT_REFUNDED));   // 고객·사장님

        Slice<NotificationResponse> customerPage0 = notificationService.getMyNotifications("customer1", PageQuery.of(0, 2));
        Slice<NotificationResponse> customerPage1 = notificationService.getMyNotifications("customer1", PageQuery.of(1, 2));
        Slice<NotificationResponse> ownerPage = notificationService.getMyNotifications("owner1", PageQuery.of(0, 20));

        assertThat(customerPage0.getContent()).extracting(NotificationResponse::eventType)
                .containsExactly(OutboxEventType.PAYMENT_COMPLETED, OutboxEventType.DELIVERY_COMPLETED);
        assertThat(customerPage0.hasNext()).isTrue();
        assertThat(customerPage1.getContent()).extracting(NotificationResponse::eventType)
                .containsExactly(OutboxEventType.PAYMENT_REFUNDED);
        assertThat(ownerPage.getContent()).extracting(NotificationResponse::eventType)
                .containsExactly(OutboxEventType.PAYMENT_COMPLETED, OutboxEventType.PAYMENT_REFUNDED);
        assertThat(ownerPage.getContent()).allSatisfy(n ->
                assertThat(n.sendStatus()).isEqualTo(NotificationSendStatus.PENDING));
        assertThat(notificationService.getMyNotifications("admin", PageQuery.of(0, 20)).getContent()).isEmpty();
    }

    // ===== 보관 기간 정리 =====

    @Test
    void 보관_기간이_지난_발행_완료_이벤트만_삭제하고_PENDING과_FAILED는_남긴다() {
        LocalDateTime old = NOW.minusDays(8); // outbox-published 7d
        // batch-size(2)보다 많은 5건 → 여러 번에 나눠 모두 삭제
        for (int i = 0; i < 5; i++) {
            insertEvent(i, "PUBLISHED", old, old);
        }
        long recentPublished = insertEvent(10, "PUBLISHED", NOW.minusDays(6), NOW.minusDays(6));
        long oldPending = insertEvent(11, "PENDING", old, old);
        long oldFailed = insertEvent(12, "FAILED", old, old);

        CleanupResult result = retentionCleaner.cleanUp();

        assertThat(result.outboxEvents()).isEqualTo(5);
        assertThat(outboxEventRepository.findAll()).extracting(OutboxEvent::getId)
                .containsExactlyInAnyOrder(recentPublished, oldPending, oldFailed);
        // 다시 실행해도 더 지우지 않음
        assertThat(retentionCleaner.cleanUp()).isEqualTo(new CleanupResult(0, 0, 0, 0));
    }

    @Test
    void 보관_기간이_지난_처리_기록과_멱등성_기록을_삭제하고_결제와_알림은_남긴다() {
        // 31일 전: 키 있는 결제 + 그 이벤트 처리 (consumed-events·payment-idempotency-keys 30d)
        clock.set(NOW.minusDays(31));
        OrderResponse oldOrder = orderService.createOrder(
                new OrderCreateRequest(List.of(new OrderItemRequest(menu.id(), 1)), "서울시"), "customer1");
        paymentService.pay(new PaymentRequest(oldOrder.id(), "CARD"), "customer1", "old-key", "fp");
        notificationService.handle(message(OutboxEventType.PAYMENT_COMPLETED));

        // 29일 전: 아직 보관 기간 안
        clock.set(NOW.minusDays(29));
        OrderResponse recentOrder = orderService.createOrder(
                new OrderCreateRequest(List.of(new OrderItemRequest(menu.id(), 1)), "서울시"), "customer1");
        paymentService.pay(new PaymentRequest(recentOrder.id(), "CARD"), "customer1", "recent-key", "fp");
        notificationService.handle(message(OutboxEventType.DELIVERY_COMPLETED));

        clock.set(NOW);
        CleanupResult result = retentionCleaner.cleanUp();

        assertThat(result.consumedEvents()).isEqualTo(1);
        assertThat(result.paymentIdempotencyKeys()).isEqualTo(1);
        assertThat(consumedEventRepository.count()).isEqualTo(1);
        assertThat(paymentService.findIdempotentPayment("customer1", "old-key")).isEmpty();
        assertThat(paymentService.findIdempotentPayment("customer1", "recent-key")).isPresent();
        // 업무 기록은 그대로
        assertThat(paymentRepository.count()).isEqualTo(2);
        assertThat(notificationRepository.count()).isEqualTo(3);
    }

    // ===== 메트릭 =====

    @Test
    void Outbox_대기_건수와_FAILED_건수_가장_오래된_대기_시간을_메트릭으로_노출한다() {
        assertThat(gauge("outbox.events", "pending")).isZero();
        assertThat(gauge("outbox.events", "failed")).isZero();
        assertThat(gauge("outbox.oldest.pending.age.seconds", null)).isZero();

        insertEvent(1, "PENDING", NOW.minusMinutes(10), NOW);
        insertEvent(2, "PENDING", NOW.minusMinutes(3), NOW);
        insertEvent(3, "FAILED", NOW.minusHours(2), NOW);
        insertEvent(4, "PUBLISHED");

        assertThat(gauge("outbox.events", "pending")).isEqualTo(2);
        assertThat(gauge("outbox.events", "failed")).isEqualTo(1);
        assertThat(gauge("outbox.oldest.pending.age.seconds", null)).isEqualTo(600); // PENDING 중 가장 오래된 10분
    }

    @Test
    void 발송_결과를_카운터로_센다() {
        double sentBefore = meterRegistry.get("notification.send").tag("result", "sent").counter().count();
        double retryBefore = meterRegistry.get("notification.send").tag("result", "retry").counter().count();
        notificationService.handle(message(OutboxEventType.PAYMENT_COMPLETED));
        sender.failingRecipients.add(owner.getId());

        dispatcher.dispatchPending();

        assertThat(meterRegistry.get("notification.send").tag("result", "sent").counter().count())
                .isEqualTo(sentBefore + 1);
        assertThat(meterRegistry.get("notification.send").tag("result", "retry").counter().count())
                .isEqualTo(retryBefore + 1);
    }
}
