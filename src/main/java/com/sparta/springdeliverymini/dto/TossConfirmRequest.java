package com.sparta.springdeliverymini.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

// 결제창 성공 후 successUrl에 붙어 온 값(paymentKey·orderId·amount) + 이 서비스의 주문 id
// amount는 화면이 보낸 값이라 그대로 믿지 않음 → 주문 총액과 다르면 토스 승인을 호출하지 않고 400
// (토스 successUrl의 orderId는 토스 주문번호라 이름이 겹치지 않도록 tossOrderId로 받음)
public record TossConfirmRequest(
        @NotNull(message = "주문 ID를 입력해야 합니다.")
        Long orderId,

        @NotBlank(message = "paymentKey를 입력해야 합니다.")
        @Size(max = 200, message = "paymentKey는 200자 이하여야 합니다.")
        String paymentKey,

        @NotBlank(message = "토스 주문번호를 입력해야 합니다.")
        @Size(min = 6, max = 64, message = "토스 주문번호는 6~64자여야 합니다.")
        String tossOrderId,

        @NotNull(message = "결제 금액을 입력해야 합니다.")
        Long amount
) {
}
