package com.sparta.springdeliverymini.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

// 메뉴 수정 요청 — 매장은 바꿀 수 없으므로 storeId를 받지 않음
// (매장을 옮기면 기존 주문의 매장과 메뉴의 매장이 달라짐)
public record MenuUpdateRequest(
        @NotBlank(message = "메뉴 이름을 입력해야 합니다.")
        @Size(max = 50, message = "메뉴 이름은 50자 이하여야 합니다.")
        String name,

        @NotNull(message = "가격을 입력해야 합니다.")
        @Min(value = 1, message = "가격은 1원 이상이어야 합니다.")
        Long price,

        @Size(max = 255, message = "설명은 255자 이하여야 합니다.")
        String description
) {
}
