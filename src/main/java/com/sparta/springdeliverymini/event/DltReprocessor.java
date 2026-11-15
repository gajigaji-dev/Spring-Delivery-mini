package com.sparta.springdeliverymini.event;

import com.sparta.springdeliverymini.config.OutboxProperties;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.PartitionInfo;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

// DLT 메시지를 원래 토픽으로 다시 발행하는 수동 재처리 도구 (#52, docs/architecture.md Kafka 장)
// 실행은 DltReprocessRunner(CLI, dlt-reprocess 프로필) — 원인을 고친 뒤 운영자가 직접 실행
//
// - 재발행: 키·본문·업무 헤더(eventId·eventType 등)는 그대로, DLT가 붙인 kafka_dlt-* 헤더만 제거
//   → 다시 실패하면 오류 처리기가 새 원인으로 헤더를 다시 붙여 DLT로 보냄
// - 중복 알림 방지의 최종 근거는 Consumer의 consumed_events(eventId PK): 같은 메시지를 여러 번 재발행해도 알림은 한 번
// - 전체 재처리(reprocessAll): 진행 위치를 Consumer 그룹(GROUP_ID) 오프셋으로 기록
//   · 한 건 재발행 성공(브로커 저장 확인) 뒤에만 그 건의 오프셋을 커밋 → 다시 실행하면 처리한 건은 건너뛰고 이어서 처리
//   · 재발행이 실패하면 그 건에서 멈춤(뒤 건도 보내지 않음) → 같은 파티션 순서를 지키고, 다시 실행하면 실패한 건부터
//   · 전송 성공 후 커밋 전에 죽으면 다음 실행에서 한 번 더 보냄 → Consumer 멱등성이 걸러냄
//   · 시작 시점의 끝 오프셋까지만 읽음 → 재처리 중 다시 실패해 DLT로 돌아온 메시지를 같은 실행에서 또 보내는 반복을 막음
// - 지정 재처리(reprocessOne): 한 건만 보냄. 그룹 오프셋은 바꾸지 않음(앞 건을 처리한 것으로 만들지 않도록)
// - 재발행해도 결과가 같은 메시지는 보내지 않고 건너뜀: 형식 오류(InvalidEventMessageException), 원래 토픽이 업무 토픽이 아닌 메시지
// - 여러 실행이 동시에 돌지 않도록 하는 락은 DltReprocessRunner가 잡음
@Slf4j
@Component
public class DltReprocessor {

    // 재처리 진행 위치를 기록하는 Consumer 그룹 (알림 Consumer 그룹 notification과 별개)
    public static final String GROUP_ID = "dlt-reprocessor";
    // DeadLetterPublishingRecoverer가 붙이는 헤더 (원래 토픽·파티션·오프셋·예외 정보)
    static final String DLT_HEADER_PREFIX = KafkaHeaders.PREFIX + "dlt-";

    private static final Duration POLL_TIMEOUT = Duration.ofMillis(500);
    // 끝 오프셋 전인데 이 시간 동안 아무것도 읽지 못하면 실패로 끝냄 (브로커 장애에서 무한 대기하지 않도록)
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(30);

    private final ConsumerFactory<String, String> consumerFactory;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final OutboxProperties properties;

    public DltReprocessor(ConsumerFactory<String, String> consumerFactory, KafkaTemplate<String, String> kafkaTemplate,
                          OutboxProperties properties) {
        this.consumerFactory = consumerFactory;
        this.kafkaTemplate = kafkaTemplate;
        this.properties = properties;
    }

    // republished: 재발행 성공 / skipped: 보내지 않고 건너뜀 / failure: 멈춘 지점(없으면 null → 전부 처리)
    public record Result(int republished, int skipped, Failure failure) {

        public boolean succeeded() {
            return failure == null;
        }
    }

    public record Failure(int partition, long offset, String reason) {
    }

    // DLT 전체 중 아직 처리하지 않은 메시지(그룹 커밋 오프셋 ~ 시작 시점 끝 오프셋)를 파티션 순서대로 재발행
    public Result reprocessAll() {
        try (Consumer<String, String> consumer = createConsumer()) {
            List<TopicPartition> partitions = partitionsOf(consumer);
            Map<TopicPartition, Long> endOffsets = consumer.endOffsets(partitions);
            Map<TopicPartition, OffsetAndMetadata> committed = consumer.committed(new HashSet<>(partitions));

            int republished = 0;
            int skipped = 0;
            for (TopicPartition partition : partitions) {
                consumer.assign(List.of(partition));
                OffsetAndMetadata start = committed.get(partition);
                if (start == null) {
                    consumer.seekToBeginning(List.of(partition));
                } else {
                    consumer.seek(partition, start.offset());
                }
                long end = endOffsets.get(partition);

                long lastProgress = System.nanoTime();
                while (consumer.position(partition) < end) {
                    List<ConsumerRecord<String, String>> records = consumer.poll(POLL_TIMEOUT).records(partition);
                    if (records.isEmpty()) {
                        if (System.nanoTime() - lastProgress > READ_TIMEOUT.toNanos()) {
                            return new Result(republished, skipped, new Failure(partition.partition(),
                                    consumer.position(partition), "DLT를 읽지 못함 (시간 초과)"));
                        }
                        continue;
                    }
                    lastProgress = System.nanoTime();
                    for (ConsumerRecord<String, String> record : records) {
                        if (record.offset() >= end) {
                            break;
                        }
                        Outcome outcome = handle(record);
                        if (outcome.failure() != null) {
                            return new Result(republished, skipped, outcome.failure());
                        }
                        if (outcome.republished()) {
                            republished++;
                        } else {
                            skipped++;
                        }
                        // 재발행이 확인된(또는 건너뛴) 건만 진행 위치로 기록
                        try {
                            consumer.commitSync(Map.of(partition, new OffsetAndMetadata(record.offset() + 1)));
                        } catch (RuntimeException e) {
                            // 이미 보낸 건이라 다시 실행하면 한 번 더 보냄 → Consumer 멱등성이 걸러냄
                            return new Result(republished, skipped, new Failure(record.partition(), record.offset(),
                                    "재발행 후 진행 위치 기록 실패: " + describe(e)));
                        }
                    }
                }
            }
            return new Result(republished, skipped, null);
        }
    }

    // DLT의 partition·offset 한 건만 재발행 (그룹 오프셋은 바꾸지 않음)
    public Result reprocessOne(int partitionNumber, long offset) {
        try (Consumer<String, String> consumer = createConsumer()) {
            TopicPartition partition = new TopicPartition(properties.deadLetterTopic(), partitionNumber);
            if (!partitionsOf(consumer).contains(partition)) {
                throw new IllegalArgumentException("DLT에 없는 파티션입니다: " + partitionNumber);
            }
            long beginning = consumer.beginningOffsets(List.of(partition)).get(partition);
            long end = consumer.endOffsets(List.of(partition)).get(partition);
            if (offset < beginning || offset >= end) {
                throw new IllegalArgumentException("DLT 파티션 " + partitionNumber + "에 없는 오프셋입니다: " + offset
                        + " (가능 범위 " + beginning + " ~ " + (end - 1) + ")");
            }

            consumer.assign(List.of(partition));
            consumer.seek(partition, offset);
            long deadline = System.nanoTime() + READ_TIMEOUT.toNanos();
            while (System.nanoTime() < deadline) {
                for (ConsumerRecord<String, String> record : consumer.poll(POLL_TIMEOUT).records(partition)) {
                    if (record.offset() != offset) {
                        // 지정한 오프셋 자리에 메시지가 없음 (트랜잭션 마커 등) → 다음 메시지를 대신 보내지 않음
                        throw new IllegalArgumentException("DLT 파티션 " + partitionNumber + " 오프셋 " + offset
                                + "에 메시지가 없습니다.");
                    }
                    Outcome outcome = handle(record);
                    return outcome.failure() != null
                            ? new Result(0, 0, outcome.failure())
                            : new Result(outcome.republished() ? 1 : 0, outcome.republished() ? 0 : 1, null);
                }
            }
            return new Result(0, 0, new Failure(partitionNumber, offset, "DLT를 읽지 못함 (시간 초과)"));
        }
    }

    // 한 건 처리 결과: 재발행 / 건너뜀 / 실패
    private record Outcome(boolean republished, Failure failure) {
    }

    private Outcome handle(ConsumerRecord<String, String> record) {
        String originalTopic = header(record, KafkaHeaders.DLT_ORIGINAL_TOPIC);
        if (!properties.topic().equals(originalTopic)) {
            log.warn("DLT 재처리 건너뜀 — 원래 토픽이 업무 토픽이 아님 partition={}, offset={}, originalTopic={}",
                    record.partition(), record.offset(), originalTopic);
            return new Outcome(false, null);
        }
        if (isInvalidMessage(record)) {
            log.warn("DLT 재처리 건너뜀 — 형식 오류 메시지는 다시 보내도 같은 결과 partition={}, offset={}, key={}, reason={}",
                    record.partition(), record.offset(), record.key(), header(record, KafkaHeaders.DLT_EXCEPTION_MESSAGE));
            return new Outcome(false, null);
        }

        // 파티션은 지정하지 않음 → 키(주문 id)로 정해져 같은 주문의 이벤트가 원래처럼 한 파티션에 모임
        ProducerRecord<String, String> republish = new ProducerRecord<>(
                originalTopic, null, record.key(), record.value(), businessHeaders(record));
        try {
            kafkaTemplate.send(republish).get(properties.publisher().sendTimeout().toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Outcome(false, new Failure(record.partition(), record.offset(), "재발행 중단: " + describe(e)));
        } catch (Exception e) {
            log.warn("DLT 재발행 실패 partition={}, offset={}, key={}", record.partition(), record.offset(), record.key(), e);
            return new Outcome(false, new Failure(record.partition(), record.offset(), "재발행 실패: " + describe(e)));
        }
        log.info("DLT 재발행 partition={}, offset={}, key={}, eventId={} → {}", record.partition(), record.offset(),
                record.key(), header(record, OutboxPublisher.HEADER_EVENT_ID), originalTopic);
        return new Outcome(true, null);
    }

    // kafka_dlt-* 를 뺀 나머지 헤더 (업무 헤더 eventId·eventType 등)
    static RecordHeaders businessHeaders(ConsumerRecord<?, ?> record) {
        RecordHeaders headers = new RecordHeaders();
        for (Header header : record.headers()) {
            if (!header.key().startsWith(DLT_HEADER_PREFIX)) {
                headers.add(header);
            }
        }
        return headers;
    }

    // 리스너 예외는 ListenerExecutionFailedException으로 감싸여 오므로 예외·원인 클래스를 모두 확인
    private static boolean isInvalidMessage(ConsumerRecord<?, ?> record) {
        String invalid = InvalidEventMessageException.class.getName();
        return invalid.equals(header(record, KafkaHeaders.DLT_EXCEPTION_FQCN))
                || invalid.equals(header(record, KafkaHeaders.DLT_EXCEPTION_CAUSE_FQCN));
    }

    private Consumer<String, String> createConsumer() {
        Properties overrides = new Properties();
        // 진행 위치는 재발행 성공 후 직접 커밋 (자동 커밋이면 보내기 전에 처리한 것으로 기록될 수 있음)
        overrides.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
        overrides.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        // DLT가 아직 없을 때 조회만으로 브로커가 기본 설정(파티션 1)으로 만들지 않도록
        // → 원래 토픽과 파티션 수가 다른 DLT가 생기면 오류 처리기가 원래 파티션 번호로 보내지 못함 (KafkaConfig)
        overrides.put(ConsumerConfig.ALLOW_AUTO_CREATE_TOPICS_CONFIG, "false");
        return consumerFactory.createConsumer(GROUP_ID, null, null, overrides);
    }

    // 파티션 번호 순서로 정렬 (실행마다 같은 순서로 처리·기록)
    private List<TopicPartition> partitionsOf(Consumer<String, String> consumer) {
        List<PartitionInfo> infos = consumer.partitionsFor(properties.deadLetterTopic());
        if (infos == null || infos.isEmpty()) {
            throw new IllegalStateException("DLT 토픽을 찾을 수 없습니다 (Kafka 접속·토픽 생성 여부 확인): "
                    + properties.deadLetterTopic());
        }
        List<TopicPartition> partitions = new ArrayList<>();
        for (PartitionInfo info : infos) {
            partitions.add(new TopicPartition(info.topic(), info.partition()));
        }
        partitions.sort((a, b) -> Integer.compare(a.partition(), b.partition()));
        return partitions;
    }

    private static String header(ConsumerRecord<?, ?> record, String name) {
        Header header = record.headers().lastHeader(name);
        return header == null ? null : new String(header.value(), StandardCharsets.UTF_8);
    }

    private static String describe(Throwable e) {
        Throwable cause = e instanceof ExecutionException && e.getCause() != null ? e.getCause() : e;
        return cause.getClass().getSimpleName() + ": " + cause.getMessage();
    }
}
