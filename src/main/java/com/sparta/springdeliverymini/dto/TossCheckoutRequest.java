package com.sparta.springdeliverymini.dto;

import jakarta.validation.constraints.NotNull;

public record TossCheckoutRequest(
        @NotNull(message = "주문 ID를 입력해야 합니다.")
        Long orderId
) {
}
