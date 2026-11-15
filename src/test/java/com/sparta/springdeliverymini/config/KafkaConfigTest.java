package com.sparta.springdeliverymini.config;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.KafkaException;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.support.SendResult;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

// DLT 적재 카운터(outbox.dlt.records)가 전송 성공 후에만 증가하는지 (#52)
// 실제 Kafka로 DLT 적재 + 카운터 증가는 OutboxKafkaIntegrationTest에서 함께 확인
class KafkaConfigTest {

    private static final Duration SECOND = Duration.ofSeconds(1);

    private final OutboxProperties properties = new OutboxProperties("delivery-events",
            new OutboxProperties.Publisher(true, SECOND, 100, 10, SECOND, Duration.ofMinutes(5), Duration.ofSeconds(10)),
            new OutboxProperties.Consumer(3, SECOND));
    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    @SuppressWarnings("unchecked")
    private final KafkaOperations<Object, Object> kafkaOperations = mock(KafkaOperations.class);
    private final DeadLetterPublishingRecoverer recoverer =
            KafkaConfig.deadLetterRecoverer(kafkaOperations, properties, meterRegistry);
    private final ConsumerRecord<String, String> failed = new ConsumerRecord<>("delivery-events", 2, 7L, "1", "{}");

    private double counter() {
        return meterRegistry.counter("outbox.dlt.records").count();
    }

    @Test
    void DLT_전송이_성공하면_원래_파티션으로_보내고_카운터를_1_올린다() {
        when(kafkaOperations.send(any(ProducerRecord.class))).thenReturn(CompletableFuture.completedFuture(mock(SendResult.class)));
        assertThat(counter()).isZero(); // 첫 유입 전에도 0으로 노출

        recoverer.accept(failed, null, new IllegalStateException("처리 실패"));

        verify(kafkaOperations).send(argThat((ProducerRecord<Object, Object> r) ->
                r.topic().equals("delivery-events-dlt") && r.partition() == 2));
        assertThat(counter()).isEqualTo(1);
    }

    @Test
    void DLT_전송이_실패하면_예외를_던지고_카운터는_올리지_않는다() {
        when(kafkaOperations.send(any(ProducerRecord.class)))
                .thenReturn(CompletableFuture.failedFuture(new KafkaException("broker unavailable")));

        // 오류 처리기가 같은 메시지를 다시 시도 → 시도마다 실패해도 카운터는 그대로
        for (int i = 0; i < 3; i++) {
            assertThatThrownBy(() -> recoverer.accept(failed, null, new IllegalStateException("처리 실패")))
                    .isInstanceOf(org.springframework.kafka.KafkaException.class)
                    .hasMessageContaining("Dead-letter publication");
        }
        assertThat(counter()).isZero();

        // 회복 후 전송 성공 → 1건만 집계
        when(kafkaOperations.send(any(ProducerRecord.class))).thenReturn(CompletableFuture.completedFuture(mock(SendResult.class)));
        recoverer.accept(failed, null, new IllegalStateException("처리 실패"));
        assertThat(counter()).isEqualTo(1);
    }
}
