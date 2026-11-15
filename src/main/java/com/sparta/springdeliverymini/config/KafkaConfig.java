package com.sparta.springdeliverymini.config;

import com.sparta.springdeliverymini.event.InvalidEventMessageException;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
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
    // - DLT 메시지에는 원래 토픽·파티션·오프셋·예외 정보가 헤더(kafka_dlt-*)로 붙음 → 원인 확인 후 수동 재처리 (event.DltReprocessor)
    // - DLT 전송까지 실패하면(Kafka 장애) 오프셋을 넘기지 않고 같은 메시지를 다시 시도 → 메시지를 잃지 않음
    // Boot가 이 빈을 자동 설정된 리스너 컨테이너 팩토리에 연결함
    @Bean
    CommonErrorHandler kafkaErrorHandler(KafkaOperations<?, ?> kafkaOperations, OutboxProperties properties,
                                         MeterRegistry meterRegistry) {
        OutboxProperties.Consumer consumer = properties.consumer();
        DefaultErrorHandler handler = new DefaultErrorHandler(deadLetterRecoverer(kafkaOperations, properties, meterRegistry),
                new FixedBackOff(consumer.retryInterval().toMillis(), consumer.retryAttempts()));
        handler.addNotRetryableExceptions(InvalidEventMessageException.class);
        return handler;
    }

    // DLT 전송기 — 보낼 토픽을 직접 지정 (원래 파티션 번호 유지), 기본 이름 규칙에 기대지 않고 위에서 만든 DLT로 보냄
    // outbox.dlt.records(DLT 적재 수)는 전송이 성공한 뒤에만 증가 (#52)
    // - 토픽을 정하는 시점(destination resolver)에 세면 전송 실패 → 같은 메시지 재시도 때마다 다시 세어 과다 집계됨
    // - publish()는 전송 결과를 기다리고(failIfSendResultIsError 기본 true) 실패면 예외 → 증가 코드까지 오지 않음
    static DeadLetterPublishingRecoverer deadLetterRecoverer(KafkaOperations<?, ?> kafkaOperations,
                                                             OutboxProperties properties, MeterRegistry meterRegistry) {
        // 처음 유입 전에도 0으로 노출 (monitoring/alerts.yml)
        Counter deadLetters = meterRegistry.counter("outbox.dlt.records");
        return new DeadLetterPublishingRecoverer(kafkaOperations,
                (record, exception) -> new TopicPartition(properties.deadLetterTopic(), record.partition())) {
            @Override
            protected void publish(ProducerRecord<Object, Object> outRecord, KafkaOperations<Object, Object> kafkaTemplate,
                                   ConsumerRecord<?, ?> inRecord) {
                super.publish(outRecord, kafkaTemplate, inRecord);
                deadLetters.increment();
            }
        };
    }
}
