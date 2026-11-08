package com.sparta.springdeliverymini.dto;

import com.sparta.springdeliverymini.entity.Order;
import com.sparta.springdeliverymini.entity.OrderStatus;

import java.time.LocalDateTime;
import java.util.List;

public record OrderResponse(
        Long id,
        Long storeId,             // 주문이 들어간 매장
        String storeName,
        List<OrderItemResponse> items,
        long totalPrice,          // 항목 금액의 합
        String deliveryAddress,
        OrderStatus status,
        LocalDateTime createdAt,  // 주문 시각 (BaseEntity)
        LocalDateTime updatedAt,  // 마지막 상태 변경 시각 (BaseEntity)
        LocalDateTime deliveredAt) { // 배달완료 시각 (배달완료 전에는 null)

    public static OrderResponse from(Order order) {
        return new OrderResponse(
                order.getId(),
                order.getStore().getId(),
                order.getStore().getName(),
                order.getOrderItems().stream().map(OrderItemResponse::from).toList(),
                order.getTotalPrice(),
                order.getDeliveryAddress(),
                order.getStatus(),
                order.getCreatedAt(),
                order.getUpdatedAt(),
                order.getDeliveredAt()
        );
    }
}
