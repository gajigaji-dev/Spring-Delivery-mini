package com.sparta.springdeliverymini.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

// application.yml의 outbox.* — Transactional Outbox 발행·Consumer 재시도 설정 (#42, docs/architecture.md Kafka 장)
// 값이 없거나 범위를 벗어나면 바인딩 단계에서 예외 → 애플리케이션 기동 실패 (운영 중이 아니라 기동 시점에 드러나도록)
@ConfigurationProperties(prefix = "outbox")
public record OutboxProperties(String topic, Publisher publisher, Consumer consumer) {

    public OutboxProperties {
        if (topic == null || topic.isBlank()) {
            throw new IllegalArgumentException("outbox.topic이 필요합니다.");
        }
        if (publisher == null || consumer == null) {
            throw new IllegalArgumentException("outbox.publisher, outbox.consumer 설정이 필요합니다.");
        }
    }

    // 재시도 한도를 넘긴 메시지를 보내는 토픽 — Spring Kafka 4 기본 규칙({topic}-dlt)과 같은 이름
    // (KafkaConfig에서 명시적으로 이 이름을 지정 → 라이브러리 기본값이 바뀌어도 미리 만든 토픽으로 보냄)
    public String deadLetterTopic() {
        return topic + "-dlt";
    }

    // enabled: 이 인스턴스에서 주기 발행을 할지 (false여도 이벤트 저장은 계속됨 → 켜진 인스턴스가 발행)
    // fixed-delay: 한 번 발행을 마친 뒤 다음 조회까지 쉬는 시간 / batch-size: 한 번에 가져오는 이벤트 수
    // max-attempts: 발행 실패가 이 횟수에 닿으면 FAILED (자동 재시도 중단)
    // initial-backoff · max-backoff: 실패 n번째 재시도 간격 = min(initial × 2^(n-1), max)
    // send-timeout: 한 번의 batch 발행 결과를 기다리는 최대 시간 (지나면 실패로 기록, 다음에 재시도)
    public record Publisher(boolean enabled, Duration fixedDelay, int batchSize, int maxAttempts,
                            Duration initialBackoff, Duration maxBackoff, Duration sendTimeout) {

        public Publisher {
            requirePositive("publisher.fixed-delay", fixedDelay);
            requirePositive("publisher.initial-backoff", initialBackoff);
            requirePositive("publisher.max-backoff", maxBackoff);
            requirePositive("publisher.send-timeout", sendTimeout);
            if (batchSize < 1 || maxAttempts < 1) {
                throw new IllegalArgumentException("outbox.publisher.batch-size, max-attempts는 1 이상이어야 합니다.");
            }
            if (maxBackoff.compareTo(initialBackoff) < 0) {
                throw new IllegalArgumentException("outbox.publisher.max-backoff는 initial-backoff 이상이어야 합니다.");
            }
        }

        // failureCount번 실패한 뒤의 재시도 간격 (지수 백오프, 최대 max-backoff)
        public Duration backoff(int failureCount) {
            Duration delay = initialBackoff;
            for (int i = 1; i < failureCount && delay.compareTo(maxBackoff) < 0; i++) {
                delay = delay.multipliedBy(2);
            }
            return delay.compareTo(maxBackoff) > 0 ? maxBackoff : delay;
        }
    }

    // retry-attempts: 처리 실패한 메시지를 같은 Consumer에서 다시 시도하는 횟수 (첫 시도 제외)
    //   → 모두 실패하면 DLT로 보냄. 형식이 잘못된 메시지는 재시도해도 같으므로 바로 DLT
    // retry-interval: 재시도 사이 간격
    public record Consumer(int retryAttempts, Duration retryInterval) {

        public Consumer {
            if (retryAttempts < 0) {
                throw new IllegalArgumentException("outbox.consumer.retry-attempts는 0 이상이어야 합니다.");
            }
            requirePositive("consumer.retry-interval", retryInterval);
        }
    }

    private static void requirePositive(String name, Duration value) {
        if (value == null || value.isNegative() || value.isZero()) {
            throw new IllegalArgumentException("outbox." + name + "는 0보다 커야 합니다: " + value);
        }
    }
}
