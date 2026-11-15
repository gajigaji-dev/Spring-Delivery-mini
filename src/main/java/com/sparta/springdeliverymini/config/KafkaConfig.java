package com.sparta.springdeliverymini.config;

import com.sparta.springdeliverymini.event.InvalidEventMessageException;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.TopicPartition;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

// Kafka 토픽·Consumer 오류 처리 (#42, docs/architecture.md Kafka 장)
// 프로듀서·컨슈머 접속 설정은 application.yml spring.kafka.* (Boot 자동 설정)
@Configuration
public class KafkaConfig {

    // 파티션 3: 메시지 키(주문 id)로 나뉘어 같은 주문의 이벤트는 한 파티션에 순서대로 쌓이고,
    // Consumer를 3개까지 늘려 나눠 처리할 수 있음. 단일 브로커(local·dev)라 복제는 1
    static final int PARTITIONS = 3;

    // 기동 시 KafkaAdmin이 없으면 만듦 (이미 있으면 그대로, Kafka에 접속하지 못해도 기동은 계속됨)
    @Bean
    NewTopic deliveryEventsTopic(OutboxProperties properties) {
        return TopicBuilder.name(properties.topic()).partitions(PARTITIONS).replicas(1).build();
    }

    // DLT는 원래 토픽과 파티션 수를 같게 둠 — DeadLetterPublishingRecoverer가 원래 파티션 번호 그대로 보내기 때문
    @Bean
    NewTopic deliveryEventsDeadLetterTopic(OutboxProperties properties) {
        return TopicBuilder.name(properties.deadLetterTopic()).partitions(PARTITIONS).replicas(1).build();
    }

    // Consumer 처리 실패 → retry-interval 간격으로 retry-attempts번 재시도 → 그래도 실패하면 DLT({topic}-dlt)로 보내고 다음 메시지 처리
    // - 형식이 잘못된 메시지(InvalidEventMessageException)는 재시도해도 결과가 같아 바로 DLT
    // - DLT 메시지에는 원래 토픽·파티션·오프셋·예외 정보가 헤더(kafka_dlt-*)로 붙음 → 원인 확인 후 수동 재처리
    // - DLT 전송까지 실패하면(Kafka 장애) 오프셋을 넘기지 않고 같은 메시지를 다시 시도 → 메시지를 잃지 않음
    // Boot가 이 빈을 자동 설정된 리스너 컨테이너 팩토리에 연결함
    @Bean
    CommonErrorHandler kafkaErrorHandler(KafkaOperations<?, ?> kafkaOperations, OutboxProperties properties,
                                         MeterRegistry meterRegistry) {
        // DLT로 보내는 메시지 수 — 처음 유입 전에도 0으로 노출 (monitoring/alerts.yml)
        Counter deadLetters = meterRegistry.counter("outbox.dlt.records");
        // 보낼 토픽을 직접 지정 (원래 파티션 번호 유지) — 기본 이름 규칙에 기대지 않고 위에서 만든 DLT로 보냄
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(kafkaOperations,
                (record, exception) -> {
                    deadLetters.increment();
                    return new TopicPartition(properties.deadLetterTopic(), record.partition());
                });
        OutboxProperties.Consumer consumer = properties.consumer();
        DefaultErrorHandler handler = new DefaultErrorHandler(recoverer,
                new FixedBackOff(consumer.retryInterval().toMillis(), consumer.retryAttempts()));
        handler.addNotRetryableExceptions(InvalidEventMessageException.class);
        return handler;
    }
}
