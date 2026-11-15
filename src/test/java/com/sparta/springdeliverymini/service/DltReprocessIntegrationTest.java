package com.sparta.springdeliverymini.service;

import com.sparta.springdeliverymini.KafkaTestcontainersConfiguration;
import com.sparta.springdeliverymini.TestcontainersConfiguration;
import com.sparta.springdeliverymini.config.OutboxProperties;
import com.sparta.springdeliverymini.entity.Notification;
import com.sparta.springdeliverymini.entity.OutboxEventType;
import com.sparta.springdeliverymini.event.DeliveryEventMessage;
import com.sparta.springdeliverymini.event.DltReprocessor;
import com.sparta.springdeliverymini.event.DltReprocessor.Result;
import com.sparta.springdeliverymini.event.OutboxPublisher;
import com.sparta.springdeliverymini.repository.ConsumedEventRepository;
import com.sparta.springdeliverymini.repository.NotificationRepository;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.KafkaException;
import org.apache.kafka.common.TopicPartition;
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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.*;

// DLT 재처리 도구를 실제 Kafka(Testcontainers)로 검증 (#52)
// - Consumer 처리 실패 → DLT(실제 오류 처리기) → 원인 해결 → 재처리 → 알림 저장 흐름
// - 재처리 성공·다시 실패, 중복 실행, 재발행 부분 실패 후 이어서 처리, 형식 오류 건너뜀, 지정 재처리
// - 다른 Kafka 테스트와 메시지가 섞이지 않도록 토픽 이름을 따로 씀 (컨텍스트·컨테이너도 따로 뜸)
// - 테스트 사이 격리: 시작 전에 재처리 그룹 위치를 DLT 끝으로 옮기고, 각 테스트는 Consumer 처리가 끝난 것을 확인한 뒤 끝냄
@SpringBootTest(properties = {
        "spring.kafka.listener.auto-startup=true",
        "spring.kafka.admin.auto-create=true",
        "outbox.topic=dlt-reprocess-test-events",
        "outbox.consumer.retry-attempts=1",
        "outbox.consumer.retry-interval=100ms"
})
@Import({TestcontainersConfiguration.class, KafkaTestcontainersConfiguration.class})
class DltReprocessIntegrationTest {

    private static final Duration WAIT = Duration.ofSeconds(60); // 첫 Consumer 그룹 참여(리밸런싱)까지 포함
    private static final long STORE_ID = 10L;
    private static final long CUSTOMER_ID = 20L;
    private static final long OWNER_ID = 30L;

    @Autowired private ObjectMapper objectMapper;
    @Autowired private OutboxProperties properties;
    @Autowired private ConsumerFactory<String, String> consumerFactory;
    @Autowired private DltReprocessor reprocessor;
    @Autowired private ConsumedEventRepository consumedEventRepository;
    @Autowired private NotificationRepository notificationRepository;
    @MockitoSpyBean private NotificationService notificationService;
    // Boot가 등록한 빈 타입이 KafkaTemplate<?, ?>라 제네릭을 지정하면 spy 대상을 찾지 못함 → raw 타입
    @SuppressWarnings("rawtypes")
    @MockitoSpyBean private KafkaTemplate kafkaTemplate;

    @BeforeEach
    void setUp() {
        skipExistingDeadLetters();
    }

    @AfterEach
    void tearDown() {
        notificationRepository.deleteAllInBatch();
        consumedEventRepository.deleteAllInBatch();
    }

    // ===== 도우미 =====

    private String payload(UUID eventId, OutboxEventType type) throws Exception {
        return objectMapper.writeValueAsString(new DeliveryEventMessage(eventId, type, 1L, null, STORE_ID,
                CUSTOMER_ID, OWNER_ID, 10000, LocalDateTime.of(2026, 11, 15, 12, 0)));
    }

    // OutboxPublisher처럼 업무 헤더(eventId·eventType)를 붙여 원래 토픽으로 보냄
    @SuppressWarnings("unchecked")
    private void send(String key, UUID eventId, OutboxEventType type) throws Exception {
        ProducerRecord<String, String> record = new ProducerRecord<>(properties.topic(), key, payload(eventId, type));
        record.headers()
                .add(OutboxPublisher.HEADER_EVENT_ID, eventId.toString().getBytes(StandardCharsets.UTF_8))
                .add(OutboxPublisher.HEADER_EVENT_TYPE, type.name().getBytes(StandardCharsets.UTF_8));
        kafkaTemplate.send(record).get(10, TimeUnit.SECONDS);
    }

    @SuppressWarnings("unchecked")
    private void sendRaw(String key, String value) throws Exception {
        kafkaTemplate.send(properties.topic(), key, value).get(10, TimeUnit.SECONDS);
    }

    // 이 eventId 처리를 DB 장애로 계속 실패시킴 → 재시도 후 DLT
    private void failHandling(UUID... eventIds) {
        Set<UUID> failing = Set.of(eventIds);
        doThrow(new DataAccessResourceFailureException("DB 장애"))
                .when(notificationService).handle(argThat(m -> m != null && failing.contains(m.eventId())));
    }

    private void recoverHandling() {
        doCallRealMethod().when(notificationService).handle(any());
    }

    private List<Notification> notificationsOf(UUID eventId) {
        return notificationRepository.findAll().stream().filter(n -> n.getEventId().equals(eventId)).toList();
    }

    // 토픽에서 key가 같은 메시지를 count개 모일 때까지 읽음 (매번 새 그룹 → 처음부터, 오프셋 순)
    @SuppressWarnings("unchecked")
    private List<ConsumerRecord<String, String>> poll(String topic, String key, int count) {
        List<ConsumerRecord<String, String>> found = new ArrayList<>();
        try (Consumer<String, String> consumer = consumerFactory.createConsumer("verifier-" + UUID.randomUUID(), null)) {
            consumer.subscribe(List.of(topic));
            long deadline = System.nanoTime() + WAIT.toNanos();
            while (System.nanoTime() < deadline && found.size() < count) {
                for (ConsumerRecord<String, String> record : consumer.poll(Duration.ofMillis(500))) {
                    if (key.equals(record.key())) {
                        found.add(record);
                    }
                }
            }
        }
        assertThat(found).as("%s에서 key=%s 메시지", topic, key).hasSize(count);
        return found;
    }

    private List<ConsumerRecord<String, String>> deadLetters(String key, int count) {
        return poll(properties.deadLetterTopic(), key, count);
    }

    // 재처리 그룹의 위치를 DLT 끝으로 옮김 → 앞 테스트에서 남은 메시지를 이 테스트의 전체 재처리 대상에서 뺌
    private void skipExistingDeadLetters() {
        Properties overrides = new Properties();
        overrides.put("enable.auto.commit", "false");
        try (Consumer<String, String> consumer =
                     consumerFactory.createConsumer(DltReprocessor.GROUP_ID, null, null, overrides)) {
            List<TopicPartition> partitions = consumer.partitionsFor(properties.deadLetterTopic()).stream()
                    .map(info -> new TopicPartition(info.topic(), info.partition())).toList();
            Map<TopicPartition, OffsetAndMetadata> ends = new HashMap<>();
            consumer.endOffsets(partitions).forEach((tp, end) -> ends.put(tp, new OffsetAndMetadata(end)));
            consumer.commitSync(ends);
        }
    }

    private static String header(ConsumerRecord<String, String> record, String name) {
        Header header = record.headers().lastHeader(name);
        return header == null ? null : new String(header.value(), StandardCharsets.UTF_8);
    }

    private static List<String> headerKeys(ConsumerRecord<String, String> record) {
        return StreamSupport.stream(record.headers().spliterator(), false).map(Header::key).toList();
    }

    // ===== 재처리 성공 =====

    @Test
    void 원인을_해결하고_재처리하면_원래_토픽으로_다시_발행되어_알림이_저장된다() throws Exception {
        UUID eventId = UUID.randomUUID();
        String key = "reprocess-" + eventId;
        failHandling(eventId);
        send(key, eventId, OutboxEventType.PAYMENT_COMPLETED);
        ConsumerRecord<String, String> dead = deadLetters(key, 1).getFirst();
        assertThat(header(dead, KafkaHeaders.DLT_ORIGINAL_TOPIC)).isEqualTo(properties.topic());
        assertThat(notificationsOf(eventId)).isEmpty();

        recoverHandling();
        assertThat(reprocessor.reprocessAll()).isEqualTo(new Result(1, 0, null));

        // 결제 완료 → 고객·사장님 알림
        await().atMost(WAIT).untilAsserted(() -> assertThat(notificationsOf(eventId)).hasSize(2));
        assertThat(consumedEventRepository.existsById(eventId)).isTrue();

        // 원래 토픽: 처음 보낸 메시지 + 재발행 메시지. 재발행분은 키·본문·업무 헤더 유지, kafka_dlt-* 헤더 없음
        ConsumerRecord<String, String> republished = poll(properties.topic(), key, 2).getLast();
        assertThat(republished.value()).isEqualTo(dead.value());
        assertThat(header(republished, OutboxPublisher.HEADER_EVENT_ID)).isEqualTo(eventId.toString());
        assertThat(header(republished, OutboxPublisher.HEADER_EVENT_TYPE)).isEqualTo("PAYMENT_COMPLETED");
        assertThat(headerKeys(dead)).anyMatch(k -> k.startsWith("kafka_dlt-"));
        assertThat(headerKeys(republished)).noneMatch(k -> k.startsWith("kafka_dlt-"));
    }

    // ===== 중복 실행 =====

    @Test
    void 같은_재처리를_다시_실행해도_다시_보내지_않고_지정_재처리로_또_보내도_알림은_한_번만_저장된다() throws Exception {
        UUID eventId = UUID.randomUUID();
        String key = "duplicate-" + eventId;
        failHandling(eventId);
        send(key, eventId, OutboxEventType.PAYMENT_COMPLETED);
        ConsumerRecord<String, String> dead = deadLetters(key, 1).getFirst();
        recoverHandling();

        assertThat(reprocessor.reprocessAll()).isEqualTo(new Result(1, 0, null));
        // 진행 위치가 기록돼 두 번째 전체 재처리는 보낼 것이 없음
        assertThat(reprocessor.reprocessAll()).isEqualTo(new Result(0, 0, null));
        await().atMost(WAIT).untilAsserted(() -> assertThat(notificationsOf(eventId)).hasSize(2));

        // 운영자가 같은 건을 지정해 또 보냄 → Kafka에는 다시 가지만 Consumer가 eventId로 걸러냄
        assertThat(reprocessor.reprocessOne(dead.partition(), dead.offset())).isEqualTo(new Result(1, 0, null));
        // 실패 1번 + 재시도 1번 + 재처리 1번 + 지정 재처리 1번
        await().atMost(WAIT).untilAsserted(() ->
                verify(notificationService, times(4)).handle(argThat(m -> m != null && eventId.equals(m.eventId()))));
        assertThat(notificationsOf(eventId)).hasSize(2);
        assertThat(consumedEventRepository.count()).isEqualTo(1);
    }

    // ===== 재처리 실패 =====

    @Test
    void 재처리한_메시지가_다시_실패하면_DLT로_돌아오고_같은_실행에서_반복해_보내지_않는다() throws Exception {
        UUID eventId = UUID.randomUUID();
        String key = "fail-again-" + eventId;
        failHandling(eventId);
        send(key, eventId, OutboxEventType.DELIVERY_COMPLETED);
        deadLetters(key, 1);

        // 원인을 해결하지 않고 재처리 → 재발행 자체는 성공, Consumer에서 다시 실패해 DLT에 새로 쌓임
        assertThat(reprocessor.reprocessAll()).isEqualTo(new Result(1, 0, null));
        List<ConsumerRecord<String, String>> twice = deadLetters(key, 2);
        assertThat(twice.getLast().offset()).isGreaterThan(twice.getFirst().offset());
        assertThat(header(twice.getLast(), KafkaHeaders.DLT_EXCEPTION_MESSAGE)).contains("DB 장애");
        assertThat(notificationsOf(eventId)).isEmpty();

        // 원인 해결 후 다시 실행 → 새로 쌓인 건만 재발행 (처음 건은 이미 처리한 위치)
        recoverHandling();
        assertThat(reprocessor.reprocessAll()).isEqualTo(new Result(1, 0, null));
        await().atMost(WAIT).untilAsserted(() -> assertThat(notificationsOf(eventId)).hasSize(1));
    }

    @Test
    void 재발행이_중간에_실패하면_그_건에서_멈추고_다시_실행하면_실패한_건부터_이어서_처리한다() throws Exception {
        // 같은 키 → 원래 토픽·DLT 모두 한 파티션에 순서대로
        String key = "partial-" + UUID.randomUUID();
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        UUID third = UUID.randomUUID();
        failHandling(first, second, third);
        send(key, first, OutboxEventType.DELIVERY_COMPLETED);
        send(key, second, OutboxEventType.DELIVERY_COMPLETED);
        send(key, third, OutboxEventType.DELIVERY_COMPLETED);
        List<ConsumerRecord<String, String>> dead = deadLetters(key, 3);
        recoverHandling();

        // 두 번째 건 재발행만 Kafka 응답 실패
        doReturn(CompletableFuture.failedFuture(new KafkaException("broker unavailable")))
                .when(kafkaTemplate).send(argThat((ProducerRecord<String, String> r) -> r != null
                        && properties.topic().equals(r.topic()) && r.value().contains(second.toString())));

        Result stopped = reprocessor.reprocessAll();
        assertThat(stopped.republished()).isEqualTo(1);
        assertThat(stopped.failure()).isNotNull();
        assertThat(stopped.failure().partition()).isEqualTo(dead.get(1).partition());
        assertThat(stopped.failure().offset()).isEqualTo(dead.get(1).offset());
        assertThat(stopped.failure().reason()).contains("broker unavailable");
        await().atMost(WAIT).untilAsserted(() -> assertThat(notificationsOf(first)).hasSize(1));
        assertThat(notificationsOf(second)).isEmpty();
        assertThat(notificationsOf(third)).isEmpty(); // 실패 뒤 건은 보내지 않음 (순서 유지)

        // Kafka 회복 후 다시 실행 → 첫 건은 다시 보내지 않고 두 번째부터
        doCallRealMethod().when(kafkaTemplate).send(any(ProducerRecord.class));
        assertThat(reprocessor.reprocessAll()).isEqualTo(new Result(2, 0, null));
        await().atMost(WAIT).untilAsserted(() -> {
            assertThat(notificationsOf(second)).hasSize(1);
            assertThat(notificationsOf(third)).hasSize(1);
        });
        assertThat(notificationsOf(first)).hasSize(1);
        // 첫 건: 실패 1 + 재시도 1 + 재처리 1 (두 번째 실행에서 다시 보내지 않음)
        verify(notificationService, times(3)).handle(argThat(m -> m != null && first.equals(m.eventId())));
    }

    // ===== 건너뜀 · 지정 재처리 =====

    @Test
    void 형식_오류로_DLT에_간_메시지는_다시_보내지_않고_건너뛴다() throws Exception {
        String key = "invalid-" + UUID.randomUUID();
        sendRaw(key, "{not-json");
        deadLetters(key, 1);

        assertThat(reprocessor.reprocessAll()).isEqualTo(new Result(0, 1, null));
        assertThat(reprocessor.reprocessAll()).isEqualTo(new Result(0, 0, null)); // 건너뛴 위치도 기록
        poll(properties.topic(), key, 1); // 원래 토픽에는 처음 보낸 것뿐
        verify(notificationService, never()).handle(any());
    }

    @Test
    void 파티션과_오프셋을_지정하면_그_한_건만_보내고_전체_재처리_위치는_바꾸지_않는다() throws Exception {
        String key = "single-" + UUID.randomUUID();
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        failHandling(first, second);
        send(key, first, OutboxEventType.DELIVERY_COMPLETED);
        send(key, second, OutboxEventType.DELIVERY_COMPLETED);
        List<ConsumerRecord<String, String>> dead = deadLetters(key, 2);
        recoverHandling();

        ConsumerRecord<String, String> target = dead.getLast();
        assertThat(reprocessor.reprocessOne(target.partition(), target.offset())).isEqualTo(new Result(1, 0, null));
        await().atMost(WAIT).untilAsserted(() -> assertThat(notificationsOf(second)).hasSize(1));
        assertThat(notificationsOf(first)).isEmpty();

        // 전체 재처리는 두 건 모두 보냄 (두 번째는 중복이지만 알림은 그대로 1건)
        assertThat(reprocessor.reprocessAll()).isEqualTo(new Result(2, 0, null));
        await().atMost(WAIT).untilAsserted(() -> assertThat(notificationsOf(first)).hasSize(1));
        await().atMost(WAIT).untilAsserted(() ->
                verify(notificationService, times(4)).handle(argThat(m -> m != null && second.equals(m.eventId()))));
        assertThat(notificationsOf(second)).hasSize(1);
    }

    @Test
    void DLT에_없는_파티션이나_오프셋을_지정하면_예외를_던지고_아무것도_보내지_않는다() {
        clearInvocations(kafkaTemplate);

        assertThatThrownBy(() -> reprocessor.reprocessOne(99, 0)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("파티션");
        assertThatThrownBy(() -> reprocessor.reprocessOne(0, Long.MAX_VALUE)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("오프셋");
        verify(kafkaTemplate, never()).send(any(ProducerRecord.class));
    }

    @Test
    void DLT_토픽이_없으면_만들지_않고_예외를_던진다() {
        // 기본 설정 Kafka는 조회만으로 토픽을 자동 생성(파티션 1) → 재처리 도구가 엉뚱한 DLT를 만들면 안 됨
        String missingTopic = "missing-" + UUID.randomUUID();
        OutboxProperties missing = new OutboxProperties(missingTopic, properties.publisher(), properties.consumer());
        @SuppressWarnings("unchecked")
        DltReprocessor reprocessorForMissing = new DltReprocessor(consumerFactory, kafkaTemplate, missing);

        assertThatThrownBy(reprocessorForMissing::reprocessAll).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(missing.deadLetterTopic());
        try (Consumer<String, String> consumer = consumerFactory.createConsumer("verifier-" + UUID.randomUUID(), null)) {
            assertThat(consumer.listTopics()).doesNotContainKey(missing.deadLetterTopic());
        }
    }
}
