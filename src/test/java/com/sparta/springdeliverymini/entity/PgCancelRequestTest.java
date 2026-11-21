package com.sparta.springdeliverymini.entity;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// PG 취소 요청 상태 전이 (V20) — PENDING에서만 처리, DONE·FAILED는 최종
class PgCancelRequestTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 21, 12, 0);

    private static PgCancelRequest request() {
        return PgCancelRequest.compensation(PgProvider.TOSS, "pk_1", 36000, "보상 취소", NOW);
    }

    @Test
    void 새_요청은_바로_시도할_수_있는_PENDING이다() {
        PgCancelRequest request = request();

        assertThat(request.getStatus()).isEqualTo(PgCancelStatus.PENDING);
        assertThat(request.getAttempts()).isZero();
        assertThat(request.getNextAttemptAt()).isEqualTo(NOW);
    }

    @Test
    void 실패하면_시도_횟수가_늘고_다음_시도_시각이_미뤄진다() {
        PgCancelRequest request = request();

        request.markFailedAttempt("시간 초과", NOW.plusSeconds(10), 3);

        assertThat(request.getStatus()).isEqualTo(PgCancelStatus.PENDING);
        assertThat(request.getAttempts()).isEqualTo(1);
        assertThat(request.getNextAttemptAt()).isEqualTo(NOW.plusSeconds(10));
        assertThat(request.getLastError()).isEqualTo("시간 초과");
    }

    @Test
    void 최대_시도_횟수만큼_실패하면_FAILED가_되고_더_처리하지_않는다() {
        PgCancelRequest request = request();

        request.markFailedAttempt("e1", NOW.plusSeconds(10), 2);
        request.markFailedAttempt("e2", NOW.plusSeconds(20), 2);

        assertThat(request.getStatus()).isEqualTo(PgCancelStatus.FAILED);
        assertThatThrownBy(() -> request.markDone(NOW)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> request.claim(NOW)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void 완료되면_완료_시각이_남고_다시_완료할_수_없다() {
        PgCancelRequest request = request();
        request.markFailedAttempt("e1", NOW.plusSeconds(10), 5);

        request.markDone(NOW.plusSeconds(11));

        assertThat(request.getStatus()).isEqualTo(PgCancelStatus.DONE);
        assertThat(request.getAttempts()).isEqualTo(2);
        assertThat(request.getCompletedAt()).isEqualTo(NOW.plusSeconds(11));
        assertThat(request.getLastError()).isNull();
        assertThatThrownBy(() -> request.markDone(NOW)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void 긴_오류_메시지와_사유는_컬럼_길이에_맞게_자른다() {
        PgCancelRequest request = PgCancelRequest.compensation(PgProvider.TOSS, "pk_1", 1, "가".repeat(300), NOW);

        request.markFailedAttempt("x".repeat(1000), NOW, 5);

        assertThat(request.getReason()).hasSize(200);
        assertThat(request.getLastError()).hasSize(500);
    }

    @Test
    void 금액이_0_이하이거나_결제_키가_없으면_만들_수_없다() {
        assertThatThrownBy(() -> PgCancelRequest.compensation(PgProvider.TOSS, "pk_1", 0, "r", NOW))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PgCancelRequest.compensation(PgProvider.TOSS, " ", 1000, "r", NOW))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
