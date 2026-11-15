package com.sparta.springdeliverymini.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

// application.yml의 notification.* — 알림 발송 설정 (event.NotificationDispatcher, docs/architecture.md Kafka 장)
// 값이 없거나 범위를 벗어나면 바인딩 단계에서 예외 → 애플리케이션 기동 실패
@ConfigurationProperties(prefix = "notification")
public record NotificationProperties(Dispatcher dispatcher) {

    public NotificationProperties {
        if (dispatcher == null) {
            throw new IllegalArgumentException("notification.dispatcher 설정이 필요합니다.");
        }
    }

    // enabled: 이 인스턴스에서 주기 발송을 할지 (false여도 알림 기록 저장은 계속됨 → 켜진 인스턴스가 발송)
    // fixed-delay: 한 번 발송을 마친 뒤 다음 조회까지 쉬는 시간 / batch-size: 한 번에 가져오는 알림 수
    // max-attempts: 발송 실패가 이 횟수에 닿으면 FAILED (자동 발송 중단)
    // retry-delay: 실패한 알림을 다시 시도하기까지의 간격 (고정 간격 — 알림은 늦게 도착할수록 가치가 떨어져 오래 재시도하지 않음)
    public record Dispatcher(boolean enabled, Duration fixedDelay, int batchSize, int maxAttempts,
                             Duration retryDelay) {

        public Dispatcher {
            requirePositive("fixed-delay", fixedDelay);
            requirePositive("retry-delay", retryDelay);
            if (batchSize < 1 || maxAttempts < 1) {
                throw new IllegalArgumentException("notification.dispatcher.batch-size, max-attempts는 1 이상이어야 합니다.");
            }
        }
    }

    private static void requirePositive(String name, Duration value) {
        if (value == null || value.isNegative() || value.isZero()) {
            throw new IllegalArgumentException("notification.dispatcher." + name + "는 0보다 커야 합니다: " + value);
        }
    }
}
