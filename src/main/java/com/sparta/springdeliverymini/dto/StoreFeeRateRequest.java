package com.sparta.springdeliverymini.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

// 매장 수수료율 설정 요청 (관리자) — 0 이상 1 이하, 소수점 4자리까지 (예: 0.03 = 3%)
// 규칙은 PaymentFee.validateRate와 같음 → 잘못된 값은 엔티티까지 가지 않고 여기서 400
// 공통 수수료율로 되돌리려면 null이 아니라 DELETE /api/admin/stores/{id}/fee-rate 사용
public record StoreFeeRateRequest(
        @NotNull(message = "수수료율을 입력해야 합니다.")
        @DecimalMin(value = "0", message = "수수료율은 0 이상이어야 합니다.")
        @DecimalMax(value = "1", message = "수수료율은 1 이하여야 합니다.")
        @Digits(integer = 1, fraction = 4, message = "수수료율은 소수점 4자리까지만 지정할 수 있습니다.")
        BigDecimal feeRate
) {
}
