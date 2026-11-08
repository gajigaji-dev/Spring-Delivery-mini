package com.sparta.springdeliverymini.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

// 금액은 요청으로 받지 않고 서버에서 항목별 메뉴 가격 × 수량의 합으로 계산
// 매장은 요청으로 받지 않고 메뉴의 매장으로 정해짐 (모든 항목이 같은 매장 메뉴여야 함)
public record OrderCreateRequest(
        @NotEmpty(message = "주문 항목을 1개 이상 담아야 합니다.")
        @Size(max = 50, message = "주문 항목은 50개 이하여야 합니다.")
        List<@NotNull(message = "주문 항목이 비어 있습니다.") @Valid OrderItemRequest> items,

        @NotBlank(message = "배송 주소를 입력해야 합니다.")
        @Size(max = 255, message = "배송 주소는 255자 이하여야 합니다.")
        String deliveryAddress
) {
}
