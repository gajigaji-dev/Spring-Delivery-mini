package com.sparta.springdeliverymini.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

// application.yml의 retention.* — 보관 기간이 지난 기록 정리 (maintenance.RetentionCleaner, docs/architecture.md Kafka 장)
// 값이 없거나 범위를 벗어나면 바인딩 단계에서 예외 → 애플리케이션 기동 실패
//
// enabled: 이 인스턴스에서 주기 정리를 할지 / fixed-delay: 정리 주기
// batch-size: 한 번의 DELETE로 지우는 최대 행 수 (긴 트랜잭션·큰 WAL 방지)
// max-batches-per-run: 한 주기에 테이블당 실행하는 최대 DELETE 횟수 (밀린 양이 많아도 한 주기가 끝없이 길어지지 않도록)
// outbox-published: PUBLISHED 이벤트 보관 기간 (PENDING·FAILED는 지우지 않음)
// consumed-events: Consumer 처리 기록 보관 기간 — 같은 메시지가 다시 올 수 있는 기간(Kafka 토픽·DLT 보관 기간)보다 길어야 함
// payment-idempotency-keys: DB 멱등성 기록 보관 기간 — 지난 뒤의 같은 키 재시도는 DB 검사 409 (재결제 없음)
// refresh-tokens: 만료된 Refresh Token 행 보관 기간 (V16) — expires_at 기준, 만료 전 행은 기간과 관계없이 남김
@ConfigurationProperties(prefix = "retention")
public record RetentionProperties(boolean enabled, Duration fixedDelay, int batchSize, int maxBatchesPerRun,
                                  Duration outboxPublished, Duration consumedEvents,
                                  Duration paymentIdempotencyKeys, Duration refreshTokens) {

    public RetentionProperties {
        requirePositive("fixed-delay", fixedDelay);
        requirePositive("outbox-published", outboxPublished);
        requirePositive("consumed-events", consumedEvents);
        requirePositive("payment-idempotency-keys", paymentIdempotencyKeys);
        requirePositive("refresh-tokens", refreshTokens);
        if (batchSize < 1 || maxBatchesPerRun < 1) {
            throw new IllegalArgumentException("retention.batch-size, max-batches-per-run은 1 이상이어야 합니다.");
        }
    }

    private static void requirePositive(String name, Duration value) {
        if (value == null || value.isNegative() || value.isZero()) {
            throw new IllegalArgumentException("retention." + name + "는 0보다 커야 합니다: " + value);
        }
    }
}
