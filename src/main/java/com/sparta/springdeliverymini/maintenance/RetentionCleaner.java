package com.sparta.springdeliverymini.maintenance;

import com.sparta.springdeliverymini.config.RetentionProperties;
import com.sparta.springdeliverymini.repository.ConsumedEventRepository;
import com.sparta.springdeliverymini.repository.OutboxEventRepository;
import com.sparta.springdeliverymini.repository.PaymentIdempotencyKeyRepository;
import com.sparta.springdeliverymini.repository.RefreshTokenRepository;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.function.IntUnaryOperator;

// 보관 기간이 지난 기록 정리 (V14, docs/architecture.md Kafka 장) — config.BackgroundTaskConfig가 주기 호출
//
// 대상: 발행이 끝난 Outbox 이벤트(PUBLISHED), Consumer 처리 기록, DB 결제 멱등성 기록, 만료된 Refresh Token(V16, expires_at 기준)
// - 모두 업무 기록이 아닌 처리용 보조 기록 → 결제·정산·알림 기록은 지우지 않음
// - PENDING·FAILED 이벤트는 기간과 관계없이 남김 (아직 발행되지 않은 이벤트)
// - batch-size건씩 나눠 삭제 (DELETE마다 별도 트랜잭션): 한 문장으로 지우면 긴 트랜잭션·큰 WAL이 생김
// - 대상 행을 SKIP LOCKED로 잡으므로 여러 인스턴스가 함께 돌아도 서로 기다리지 않음
// - 한 테이블 정리가 실패해도 나머지 테이블은 계속 정리
// - 메트릭: retention.deleted{table}
@Slf4j
@Component
@RequiredArgsConstructor
public class RetentionCleaner {

    private final OutboxEventRepository outboxEventRepository;
    private final ConsumedEventRepository consumedEventRepository;
    private final PaymentIdempotencyKeyRepository idempotencyKeyRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final RetentionProperties properties;
    private final Clock clock;
    private final MeterRegistry meterRegistry;

    public CleanupResult cleanUp() {
        LocalDateTime now = LocalDateTime.now(clock);
        LocalDateTime outboxBefore = now.minus(properties.outboxPublished());
        LocalDateTime consumedBefore = now.minus(properties.consumedEvents());
        LocalDateTime keysBefore = now.minus(properties.paymentIdempotencyKeys());
        LocalDateTime refreshTokensBefore = now.minus(properties.refreshTokens());

        long outbox = deleteInBatches("outbox_events",
                limit -> outboxEventRepository.deletePublishedBefore(outboxBefore, limit));
        long consumed = deleteInBatches("consumed_events",
                limit -> consumedEventRepository.deleteConsumedBefore(consumedBefore, limit));
        long keys = deleteInBatches("payment_idempotency_keys",
                limit -> idempotencyKeyRepository.deleteCreatedBefore(keysBefore, limit));
        long refreshTokens = deleteInBatches("refresh_tokens",
                limit -> refreshTokenRepository.deleteExpiredBefore(refreshTokensBefore, limit));
        return new CleanupResult(outbox, consumed, keys, refreshTokens);
    }

    // batch-size보다 적게 지워지면 더 지울 것이 없으므로 끝냄. max-batches-per-run에 닿으면 나머지는 다음 주기에
    private long deleteInBatches(String table, IntUnaryOperator deleteBatch) {
        long total = 0;
        try {
            for (int i = 0; i < properties.maxBatchesPerRun(); i++) {
                int deleted = deleteBatch.applyAsInt(properties.batchSize());
                total += deleted;
                if (deleted < properties.batchSize()) {
                    break;
                }
            }
        } catch (RuntimeException e) {
            log.error("보관 기간 정리 실패 — 다음 주기에 다시 시도 table={}, deletedSoFar={}", table, total, e);
        }
        if (total > 0) {
            meterRegistry.counter("retention.deleted", "table", table).increment(total);
            log.info("보관 기간 정리 table={}, deleted={}", table, total);
        }
        return total;
    }

    public record CleanupResult(long outboxEvents, long consumedEvents, long paymentIdempotencyKeys,
                                long refreshTokens) {
    }
}
