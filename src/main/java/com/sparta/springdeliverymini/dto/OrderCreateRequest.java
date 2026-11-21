package com.sparta.springdeliverymini.dto;

import jakarta.validation.Valid;
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

        // 배송 주소 (#77) — 주소 검색(다음 우편번호 서비스) 결과의 우편번호·도로명 주소 + 사용자가 입력한 상세 주소
        // 형식 검증은 entity.DeliveryAddress에서 (위반 시 400 + code INVALID_ADDRESS)
        // 예전 한 줄 주소(deliveryAddress)는 받지 않음 — 보내도 무시되고 우편번호 누락으로 400
        String zipCode,
        String roadAddress,
        String detailAddress            // 선택 (단독 주택 등)
) {
}
