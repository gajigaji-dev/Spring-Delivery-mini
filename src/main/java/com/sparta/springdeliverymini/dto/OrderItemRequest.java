package com.sparta.springdeliverymini.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

// 주문 항목 요청 — 메뉴 1종과 수량 (같은 메뉴는 한 항목으로 합쳐서 요청)
public record OrderItemRequest(
        @NotNull(message = "메뉴 ID를 입력해야 합니다.")
        Long menuId,

        @NotNull(message = "수량을 입력해야 합니다.")
        @Min(value = 1, message = "수량은 1개 이상이어야 합니다.")
        Integer quantity
) {
}
