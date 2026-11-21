package com.sparta.springdeliverymini.pg;

import com.sparta.springdeliverymini.pg.FakeTossPaymentsClient.Scenario;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// 가짜 PG(V21)가 실제 토스와 같은 약속(확정 거절 / 결과 불명 / 멱등 키 / 잔액 안의 부분 취소)을 지키는지
class FakeTossPaymentsClientTest {

    private static final String ORDER_ID = "order-1-0123456789abcdef";

    private final FakeTossPaymentsClient client = new FakeTossPaymentsClient(
            Clock.fixed(Instant.parse("2026-11-21T03:00:00Z"), ZoneId.of("Asia/Seoul")));

    @Test
    void 결제창에서_인증한_금액으로_승인하면_DONE이고_승인_시각이_있다() {
        String key = client.authorize(ORDER_ID, 36000, Scenario.SUCCESS);

        TossPayment payment = client.confirm(key, ORDER_ID, 36000, "confirm-" + key);

        assertThat(key).startsWith(FakeTossPaymentsClient.PAYMENT_KEY_PREFIX);
        assertThat(payment.isDone()).isTrue();
        assertThat(payment.approvedAt()).isEqualTo("2026-11-21T12:00+09:00");
    }

    @Test
    void 인증한_금액과_다른_승인은_거절한다() {
        String key = client.authorize(ORDER_ID, 36000, Scenario.SUCCESS);

        assertThatThrownBy(() -> client.confirm(key, ORDER_ID, 100, null)).isInstanceOf(TossApiException.class);
    }

    @Test
    void 같은_멱등_키는_처음_결과를_주고_키_없이_다시_승인하면_이미_처리됨() {
        String key = client.authorize(ORDER_ID, 36000, Scenario.SUCCESS);
        TossPayment first = client.confirm(key, ORDER_ID, 36000, "k1");

        assertThat(client.confirm(key, ORDER_ID, 36000, "k1")).isEqualTo(first);
        assertThatThrownBy(() -> client.confirm(key, ORDER_ID, 36000, null))
                .isInstanceOfSatisfying(TossApiException.class,
                        e -> assertThat(e.code()).isEqualTo(TossApiException.ALREADY_PROCESSED_PAYMENT));
    }

    @Test
    void 카드_거절_시나리오는_확정_거절이고_결제는_ABORTED() {
        String key = client.authorize(ORDER_ID, 36000, Scenario.CARD_REJECTED);

        assertThatThrownBy(() -> client.confirm(key, ORDER_ID, 36000, "k"))
                .isInstanceOfSatisfying(TossApiException.class, e -> assertThat(e.code()).isEqualTo("REJECT_CARD_COMPANY"));
        assertThat(client.getPayment(key).isClosedWithoutApproval()).isTrue();
    }

    @Test
    void 응답_유실_시나리오는_결과_불명이지만_실제로는_승인돼_조회하면_DONE() {
        String key = client.authorize(ORDER_ID, 36000, Scenario.RESPONSE_LOST);

        assertThatThrownBy(() -> client.confirm(key, ORDER_ID, 36000, "k")).isInstanceOf(TossUnknownResultException.class);
        assertThat(client.getPayment(key).isDone()).isTrue();
    }

    @Test
    void PG_지연_시나리오는_첫_요청이_처리되지_않고_재시도에서_승인된다() {
        String key = client.authorize(ORDER_ID, 36000, Scenario.PG_DELAYED);

        assertThatThrownBy(() -> client.confirm(key, ORDER_ID, 36000, "k")).isInstanceOf(TossUnknownResultException.class);
        assertThat(client.getPayment(key).status()).isEqualTo("IN_PROGRESS");
        assertThat(client.confirm(key, ORDER_ID, 36000, "k").isDone()).isTrue();
    }

    @Test
    void 부분_취소는_잔액_안에서만_되고_전액_취소_뒤에는_이미_취소됨() {
        String key = client.authorize(ORDER_ID, 36000, Scenario.SUCCESS);
        client.confirm(key, ORDER_ID, 36000, null);

        assertThat(client.cancel(key, "부분", 6000L, "c1").balanceAmount()).isEqualTo(30000);
        assertThatThrownBy(() -> client.cancel(key, "초과", 30001L, "c2")).isInstanceOf(TossApiException.class);
        TossPayment rest = client.cancel(key, "나머지", null, "c3");

        assertThat(rest.isCanceled()).isTrue();
        assertThat(rest.balanceAmount()).isZero();
        assertThatThrownBy(() -> client.cancel(key, "다시", null, "c4"))
                .isInstanceOfSatisfying(TossApiException.class,
                        e -> assertThat(e.code()).isEqualTo(TossApiException.ALREADY_CANCELED_PAYMENT));
    }
}
