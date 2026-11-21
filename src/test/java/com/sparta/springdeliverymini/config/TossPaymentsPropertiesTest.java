package com.sparta.springdeliverymini.config;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// 토스 키·시간 설정 검증 — 잘못된 설정은 기동 단계에서 막음 (V20)
class TossPaymentsPropertiesTest {

    private static final TossPaymentsProperties.Cancel CANCEL = new TossPaymentsProperties.Cancel(true,
            Duration.ofSeconds(10), 20, 10, Duration.ofSeconds(10), Duration.ofMinutes(10), Duration.ofMinutes(1));

    private static TossPaymentsProperties properties(String clientKey, String secretKey) {
        return new TossPaymentsProperties(clientKey, secretKey, null, Duration.ofSeconds(3), Duration.ofSeconds(15),
                Duration.ofSeconds(5), Duration.ofSeconds(15), CANCEL, null);
    }

    @Test
    void 키가_둘_다_없으면_토스_결제를_끈다() {
        TossPaymentsProperties properties = properties("", null);

        assertThat(properties.enabled()).isFalse();
        assertThat(properties.baseUrl()).isEqualTo(URI.create("https://api.tosspayments.com"));
    }

    @Test
    void 가짜_PG_모드는_키_없이도_켜진다() {
        TossPaymentsProperties properties = new TossPaymentsProperties(null, null, null, Duration.ofSeconds(3),
                Duration.ofSeconds(15), Duration.ofSeconds(5), Duration.ofSeconds(15), CANCEL,
                TossPaymentsProperties.Mode.FAKE);

        assertThat(properties.enabled()).isTrue();
    }

    @Test
    void 테스트_키가_둘_다_있으면_켠다() {
        assertThat(properties("test_ck_abc", "test_sk_abc").enabled()).isTrue();
    }

    @Test
    void 키가_하나만_있으면_기동을_막는다() {
        assertThatThrownBy(() -> properties("test_ck_abc", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("함께");
    }

    @Test
    void 운영_키는_테스트_모드에서_쓸_수_없다() {
        assertThatThrownBy(() -> properties("live_ck_abc", "test_sk_abc"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> properties("test_ck_abc", "live_sk_abc"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageNotContaining("live_sk_abc");
    }

    @Test
    void 문자열로_찍혀도_시크릿_키는_가린다() {
        assertThat(properties("test_ck_abc", "test_sk_secret").toString())
                .contains("test_ck_abc")
                .doesNotContain("test_sk_secret");
    }

    @Test
    void 취소_임대_시간이_호출_상한보다_짧으면_기동을_막는다() {
        TossPaymentsProperties.Cancel shortLease = new TossPaymentsProperties.Cancel(true, Duration.ofSeconds(10), 20,
                10, Duration.ofSeconds(10), Duration.ofMinutes(10), Duration.ofSeconds(5));

        assertThatThrownBy(() -> new TossPaymentsProperties(null, null, null, Duration.ofSeconds(3),
                Duration.ofSeconds(15), Duration.ofSeconds(5), Duration.ofSeconds(15), shortLease, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("lease");
    }

    @Test
    void 취소_재시도_간격은_두_배씩_늘고_최대값에서_멈춘다() {
        assertThat(CANCEL.backoff(1)).isEqualTo(Duration.ofSeconds(10));
        assertThat(CANCEL.backoff(2)).isEqualTo(Duration.ofSeconds(20));
        assertThat(CANCEL.backoff(4)).isEqualTo(Duration.ofSeconds(80));
        assertThat(CANCEL.backoff(30)).isEqualTo(Duration.ofMinutes(10));
    }
}
