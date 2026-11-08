package com.sparta.springdeliverymini.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

// 금액은 요청으로 받지 않고 서버에서 메뉴 가격 × 수량으로 계산
public record OrderCreateRequest(
        @NotNull(message = "메뉴 ID를 입력해야 합니다.")
        Long menuId,

        @NotNull(message = "수량을 입력해야 합니다.")
        @Min(value = 1, message = "수량은 1개 이상이어야 합니다.")
        Integer quantity,

        @NotBlank(message = "배송 주소를 입력해야 합니다.")
        @Size(max = 255, message = "배송 주소는 255자 이하여야 합니다.")
        String deliveryAddress
) {
}
