package com.sparta.springdeliverymini.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

// 부분 환불 요청 (V21) — 금액은 원 단위 양수, 남은 결제 금액을 넘으면 400 INVALID_REFUND_AMOUNT
public record PartialRefundRequest(
        @NotNull(message = "환불 금액을 입력해야 합니다.")
        @Positive(message = "환불 금액은 0보다 커야 합니다.")
        Long amount,

        @Size(max = 200, message = "환불 사유는 200자 이하여야 합니다.")
        String reason
) {
}
