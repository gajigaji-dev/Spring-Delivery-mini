package com.sparta.springdeliverymini.dto;

import com.sparta.springdeliverymini.entity.OrderItem;

// 주문 항목 응답 — 이름·단가는 현재 메뉴가 아닌 주문 당시 값(스냅샷)
public record OrderItemResponse(
        Long menuId,
        String menuName,   // 주문 당시 메뉴 이름
        long unitPrice,    // 주문 당시 메뉴 단가
        int quantity,
        long linePrice) {  // 단가 × 수량

    public static OrderItemResponse from(OrderItem item) {
        return new OrderItemResponse(
                item.getMenu().getId(), // 지연 로딩 프록시여도 id는 메뉴 조회 없이 반환
                item.getMenuName(),
                item.getUnitPrice(),
                item.getQuantity(),
                item.getLinePrice()
        );
    }
}
