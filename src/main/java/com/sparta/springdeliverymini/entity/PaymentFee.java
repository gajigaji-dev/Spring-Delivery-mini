package com.sparta.springdeliverymini.entity;

import java.math.BigDecimal;
import java.math.RoundingMode;

// 결제 1건의 플랫폼 수수료 계산 결과 (값 객체)
//
// 금액 정책
// - 원 단위 금액(가격·총액·결제액·수수료·정산액)은 모두 long으로 저장 → 소수점 없는 정수 금액
// - 소수점이 생기는 계산(금액 × 수수료율)만 BigDecimal로 계산한 뒤 원 단위로 반올림
//   (double은 0.1 같은 값을 정확히 표현하지 못해 오차가 생기므로 사용하지 않음)
// - 반올림: 수수료를 원 단위 HALF_UP(0.5원 이상 올림)으로 한 번만 반올림
// - 정산액은 따로 반올림하지 않고 "결제액 - 수수료"로 계산 → 수수료 + 정산액 = 결제액이 항상 성립
// - 수수료율은 소수점 4자리(0.01%)까지, 0 이상 1 이하 (DB: numeric(5,4))
public record PaymentFee(BigDecimal rate, long feeAmount, long settlementAmount) {

    public static final RoundingMode ROUNDING_MODE = RoundingMode.HALF_UP;
    public static final int RATE_SCALE = 4;

    public static PaymentFee calculate(long amount, BigDecimal rate) {
        if (amount < 0) {
            throw new IllegalArgumentException("결제 금액은 0 이상이어야 합니다: " + amount);
        }
        validateRate(rate);

        // 수수료율이 1 이하이므로 수수료는 결제액을 넘지 않음 → long 범위 안 (longValueExact로 한 번 더 보장)
        long feeAmount = BigDecimal.valueOf(amount)
                .multiply(rate)
                .setScale(0, ROUNDING_MODE)
                .longValueExact();

        // DB(numeric(5,4))에 저장되는 값과 같도록 자릿수를 4자리로 맞춤 (0.05 → 0.0500)
        return new PaymentFee(rate.setScale(RATE_SCALE), feeAmount, amount - feeAmount);
    }

    // 수수료율 검증 — 설정값(PaymentFeeProperties) 바인딩 시에도 호출해 잘못된 설정이면 기동 실패
    public static void validateRate(BigDecimal rate) {
        if (rate == null) {
            throw new IllegalArgumentException("수수료율을 지정해야 합니다.");
        }
        if (rate.signum() < 0 || rate.compareTo(BigDecimal.ONE) > 0) {
            throw new IllegalArgumentException("수수료율은 0 이상 1 이하여야 합니다: " + rate);
        }
        if (rate.stripTrailingZeros().scale() > RATE_SCALE) {
            throw new IllegalArgumentException("수수료율은 소수점 " + RATE_SCALE + "자리까지만 지정할 수 있습니다: " + rate);
        }
    }
}
