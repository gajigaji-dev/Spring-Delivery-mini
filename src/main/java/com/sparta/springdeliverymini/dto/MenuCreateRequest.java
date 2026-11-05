package com.sparta.springdeliverymini.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

// 메뉴 등록 요청 — 어느 매장의 메뉴인지 storeId로 지정 (본인 매장인지는 Service에서 확인)
public record MenuCreateRequest(
        @NotNull(message = "매장 ID를 입력해야 합니다.")
        Long storeId,

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
