package com.sparta.springdeliverymini.entity;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// 수수료 계산·반올림 정책 검증 (DB 없이 실행)
class PaymentFeeTest {

    private static final BigDecimal RATE = new BigDecimal("0.05"); // application.yml 기본값 5%

    @Test
    void 나누어떨어지는_금액은_그대로_수수료가_된다() {
        PaymentFee fee = PaymentFee.calculate(36000, RATE); // 36000 × 0.05 = 1800

        assertThat(fee.feeAmount()).isEqualTo(1800);
        assertThat(fee.settlementAmount()).isEqualTo(34200);
    }

    // HALF_UP: 0.5원 이상 올림, 미만 버림
    @ParameterizedTest(name = "{0}원 × {1} = {2}원")
    @CsvSource({
            "18009, 0.05,  900",   // 900.45 → 버림
            "18010, 0.05,  901",   // 900.5  → 올림 (HALF_EVEN이면 900)
            "15,    0.1,   2",     // 1.5    → 올림 (HALF_EVEN이면 2)
            "25,    0.1,   3",     // 2.5    → 올림 (HALF_EVEN이면 2 → HALF_UP임을 구분)
            "4,     0.1,   0",     // 0.4    → 버림
            "1,     0.5,   1",     // 0.5    → 올림
    })
    void 수수료는_원_단위_HALF_UP으로_반올림한다(long amount, BigDecimal rate, long expectedFee) {
        PaymentFee fee = PaymentFee.calculate(amount, rate);

        assertThat(fee.feeAmount()).isEqualTo(expectedFee);
    }

    @ParameterizedTest
    @ValueSource(longs = {0, 1, 15, 18005, 18010, 99999, 2_147_483_648L})
    void 수수료와_정산액의_합은_항상_결제액과_같다(long amount) {
        PaymentFee fee = PaymentFee.calculate(amount, RATE);

        assertThat(fee.feeAmount() + fee.settlementAmount()).isEqualTo(amount);
    }

    @Test
    void int_범위를_넘는_금액도_정확히_계산한다() {
        // double로 계산하면 정밀도 손실이 생길 수 있는 큰 금액
        long amount = 9_007_199_254_740_993L; // 2^53 + 1
        PaymentFee fee = PaymentFee.calculate(amount, RATE);

        // 9007199254740993 × 0.05 = 450359962737049.65 → 450359962737050
        assertThat(fee.feeAmount()).isEqualTo(450_359_962_737_050L);
        assertThat(fee.settlementAmount()).isEqualTo(amount - 450_359_962_737_050L);
    }

    @Test
    void long_최대값에서도_넘치지_않는다() {
        PaymentFee fee = PaymentFee.calculate(Long.MAX_VALUE, BigDecimal.ONE);

        assertThat(fee.feeAmount()).isEqualTo(Long.MAX_VALUE);
        assertThat(fee.settlementAmount()).isZero();
    }

    @Test
    void 수수료율이_0이면_수수료는_0이고_전액_정산된다() {
        PaymentFee fee = PaymentFee.calculate(36000, BigDecimal.ZERO);

        assertThat(fee.feeAmount()).isZero();
        assertThat(fee.settlementAmount()).isEqualTo(36000);
    }

    @Test
    void 수수료율은_DB와_같은_소수점_4자리로_맞춰진다() {
        PaymentFee fee = PaymentFee.calculate(36000, RATE);

        assertThat(fee.rate()).isEqualByComparingTo("0.05");
        assertThat(fee.rate().scale()).isEqualTo(PaymentFee.RATE_SCALE); // 0.0500
    }

    @Test
    void 음수_금액은_계산할_수_없다() {
        assertThatThrownBy(() -> PaymentFee.calculate(-1, RATE))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"-0.01", "1.0001", "0.12345"})
    void 범위를_벗어나거나_소수점_4자리를_넘는_수수료율은_거부한다(String rate) {
        assertThatThrownBy(() -> PaymentFee.calculate(36000, new BigDecimal(rate)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 수수료율이_없으면_거부한다() {
        assertThatThrownBy(() -> PaymentFee.calculate(36000, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 끝자리_0은_자릿수로_세지_않는다() {
        // 0.12340은 실제로 소수점 4자리 → 허용
        PaymentFee fee = PaymentFee.calculate(10000, new BigDecimal("0.12340"));

        assertThat(fee.feeAmount()).isEqualTo(1234);
    }
}
