package com.sparta.springdeliverymini.entity;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// 알림 발송 상태 전이 — PENDING → SENT / 실패 시 재시도 예약 → 한도 초과 FAILED (V14)
class NotificationTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 14, 12, 0);
    private static final Duration RETRY_DELAY = Duration.ofMinutes(1);

    private static Notification pending() {
        return new Notification(UUID.randomUUID(), OutboxEventType.PAYMENT_COMPLETED, 2L, 10L, "결제 완료", NOW);
    }

    @Test
    void 새_알림은_PENDING이고_바로_발송_대상이다() {
        Notification notification = pending();

        assertThat(notification.getSendStatus()).isEqualTo(NotificationSendStatus.PENDING);
        assertThat(notification.getSendAttemptCount()).isZero();
        assertThat(notification.getNextSendAt()).isEqualTo(NOW);
        assertThat(notification.getSentAt()).isNull();
    }

    @Test
    void 발송하면_SENT와_발송_시각이_기록된다() {
        Notification notification = pending();

        notification.markSent(NOW.plusSeconds(5));

        assertThat(notification.getSendStatus()).isEqualTo(NotificationSendStatus.SENT);
        assertThat(notification.getSentAt()).isEqualTo(NOW.plusSeconds(5));
    }

    @Test
    void 이미_발송된_알림은_다시_발송하거나_실패로_기록할_수_없다() {
        Notification notification = pending();
        notification.markSent(NOW);

        assertThatThrownBy(() -> notification.markSent(NOW)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> notification.recordSendFailure("error", NOW, 5, RETRY_DELAY))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void 발송에_실패하면_횟수와_원인을_남기고_재시도를_미룬다() {
        Notification notification = pending();

        notification.recordSendFailure("timeout", NOW, 5, RETRY_DELAY);

        assertThat(notification.getSendStatus()).isEqualTo(NotificationSendStatus.PENDING);
        assertThat(notification.getSendAttemptCount()).isEqualTo(1);
        assertThat(notification.getLastSendError()).isEqualTo("timeout");
        assertThat(notification.getNextSendAt()).isEqualTo(NOW.plus(RETRY_DELAY));
    }

    @Test
    void 실패_횟수가_한도에_닿으면_FAILED로_자동_발송을_멈춘다() {
        Notification notification = pending();

        notification.recordSendFailure("e1", NOW, 2, RETRY_DELAY);
        notification.recordSendFailure("e2", NOW.plusMinutes(1), 2, RETRY_DELAY);

        assertThat(notification.getSendStatus()).isEqualTo(NotificationSendStatus.FAILED);
        assertThat(notification.getSendAttemptCount()).isEqualTo(2);
        assertThat(notification.getLastSendError()).isEqualTo("e2");
        assertThat(notification.getSentAt()).isNull();
        assertThatThrownBy(() -> notification.markSent(NOW)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void 실패_원인은_최대_길이로_자르고_비어_있으면_unknown으로_남긴다() {
        Notification longError = pending();
        longError.recordSendFailure("x".repeat(1000), NOW, 5, RETRY_DELAY);
        assertThat(longError.getLastSendError()).hasSize(Notification.MAX_ERROR_LENGTH);

        Notification blankError = pending();
        blankError.recordSendFailure(" ", NOW, 5, RETRY_DELAY);
        assertThat(blankError.getLastSendError()).isEqualTo("unknown");
    }
}
