package com.sparta.springdeliverymini.dto;

import com.sparta.springdeliverymini.entity.Order;
import com.sparta.springdeliverymini.entity.OrderStatus;

import java.time.LocalDateTime;

public record OrderResponse(
        Long id,
        Long menuId,
        String menuName,
        int quantity,
        int totalPrice,
        String deliveryAddress,
        OrderStatus status,
        LocalDateTime createdAt,  // 주문 시각 (BaseEntity)
        LocalDateTime updatedAt) { // 마지막 상태 변경 시각 (BaseEntity)

    public static OrderResponse from(Order order) {
        return new OrderResponse(
                order.getId(),
                order.getMenu().getId(),
                order.getMenu().getName(),
                order.getQuantity(),
                order.getTotalPrice(),
                order.getDeliveryAddress(),
                order.getStatus(),
                order.getCreatedAt(),
                order.getUpdatedAt()
        );
    }
}
