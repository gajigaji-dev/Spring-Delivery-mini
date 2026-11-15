package com.sparta.springdeliverymini.entity;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// Outbox 이벤트 상태 전이 — PENDING → PUBLISHED / 실패 시 재시도 예약 → 한도 초과 FAILED (#42)
class OutboxEventTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 14, 12, 0);

    private static OutboxEvent pending() {
        return OutboxEvent.pending(UUID.randomUUID(), OutboxEventType.PAYMENT_COMPLETED, 10L, "{}", NOW);
    }

    @Test
    void 새_이벤트는_PENDING이고_바로_발행_대상이다() {
        OutboxEvent event = pending();

        assertThat(event.getStatus()).isEqualTo(OutboxEventStatus.PENDING);
        assertThat(event.getAggregateType()).isEqualTo(OutboxEvent.AGGREGATE_ORDER);
        assertThat(event.getAggregateId()).isEqualTo(10L);
        assertThat(event.getAttemptCount()).isZero();
        assertThat(event.getNextAttemptAt()).isEqualTo(NOW);
        assertThat(event.getPublishedAt()).isNull();
    }

    @Test
    void 필수_값이_없으면_만들_수_없다() {
        assertThatThrownBy(() -> OutboxEvent.pending(null, OutboxEventType.PAYMENT_COMPLETED, 10L, "{}", NOW))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> OutboxEvent.pending(UUID.randomUUID(), OutboxEventType.PAYMENT_COMPLETED, null, "{}", NOW))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 발행하면_PUBLISHED와_발행_시각이_기록된다() {
        OutboxEvent event = pending();

        event.markPublished(NOW.plusSeconds(1));

        assertThat(event.getStatus()).isEqualTo(OutboxEventStatus.PUBLISHED);
        assertThat(event.getPublishedAt()).isEqualTo(NOW.plusSeconds(1));
    }

    @Test
    void 이미_발행된_이벤트는_다시_발행하거나_실패로_기록할_수_없다() {
        OutboxEvent event = pending();
        event.markPublished(NOW);

        assertThatThrownBy(() -> event.markPublished(NOW)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> event.recordFailure("err", NOW, 3, Duration.ofSeconds(1)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void 발행에_실패하면_횟수와_원인을_남기고_백오프_뒤로_재시도를_미룬다() {
        OutboxEvent event = pending();

        event.recordFailure("TimeoutException: broker down", NOW, 3, Duration.ofSeconds(2));

        assertThat(event.getStatus()).isEqualTo(OutboxEventStatus.PENDING);
        assertThat(event.getAttemptCount()).isEqualTo(1);
        assertThat(event.getLastError()).isEqualTo("TimeoutException: broker down");
        assertThat(event.getNextAttemptAt()).isEqualTo(NOW.plusSeconds(2));
    }

    @Test
    void 실패_횟수가_한도에_닿으면_FAILED로_자동_재시도를_멈춘다() {
        OutboxEvent event = pending();

        event.recordFailure("e1", NOW, 2, Duration.ofSeconds(1));
        event.recordFailure("e2", NOW.plusSeconds(1), 2, Duration.ofSeconds(2));

        assertThat(event.getStatus()).isEqualTo(OutboxEventStatus.FAILED);
        assertThat(event.getAttemptCount()).isEqualTo(2);
        assertThat(event.getLastError()).isEqualTo("e2");
        assertThatThrownBy(() -> event.markPublished(NOW)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void 실패_원인은_최대_길이로_자르고_비어_있으면_unknown으로_남긴다() {
        OutboxEvent longError = pending();
        longError.recordFailure("x".repeat(1000), NOW, 5, Duration.ofSeconds(1));
        assertThat(longError.getLastError()).hasSize(OutboxEvent.MAX_ERROR_LENGTH);

        OutboxEvent blankError = pending();
        blankError.recordFailure(" ", NOW, 5, Duration.ofSeconds(1));
        assertThat(blankError.getLastError()).isEqualTo("unknown");
    }

    // ===== 관리자 재발행 (V14) =====

    @Test
    void FAILED_이벤트를_재발행하면_PENDING으로_돌아가고_실패_횟수가_초기화된다() {
        OutboxEvent event = pending();
        event.recordFailure("broker down", NOW, 1, Duration.ofSeconds(1));
        assertThat(event.getStatus()).isEqualTo(OutboxEventStatus.FAILED);

        event.republish(NOW.plusHours(1));

        assertThat(event.getStatus()).isEqualTo(OutboxEventStatus.PENDING);
        assertThat(event.getAttemptCount()).isZero(); // 재시도 한도를 처음부터 다시 적용
        assertThat(event.getNextAttemptAt()).isEqualTo(NOW.plusHours(1));
        assertThat(event.getLastError()).isEqualTo("broker down"); // 원인은 확인용으로 남김
    }

    @Test
    void FAILED가_아닌_이벤트는_재발행할_수_없다() {
        OutboxEvent pending = pending();
        assertThatThrownBy(() -> pending.republish(NOW))
                .isInstanceOf(com.sparta.springdeliverymini.exception.InvalidOutboxEventStatusException.class);

        OutboxEvent published = pending();
        published.markPublished(NOW);
        assertThatThrownBy(() -> published.republish(NOW))
                .isInstanceOf(com.sparta.springdeliverymini.exception.InvalidOutboxEventStatusException.class);
    }
}
