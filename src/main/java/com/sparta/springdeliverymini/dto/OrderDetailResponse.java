package com.sparta.springdeliverymini.dto;

import com.sparta.springdeliverymini.entity.Order;
import com.sparta.springdeliverymini.entity.OrderStatus;
import com.sparta.springdeliverymini.entity.Payment;
import com.sparta.springdeliverymini.entity.PaymentMethod;
import com.sparta.springdeliverymini.entity.PaymentStatus;

import java.time.LocalDateTime;
import java.util.List;

// 주문 단건 조회 응답 (#62) — 목록 응답(OrderResponse)과 같은 필드 + 결제 정보
// 주문 상세 화면에서 결제 상태(취소·환불)까지 한 번에 보여주기 위해 결제를 함께 담음 (결제 전 주문이면 null)
public record OrderDetailResponse(
        Long id,
        Long storeId,
        String storeName,
        List<OrderItemResponse> items,
        long totalPrice,
        String deliveryAddress,
        String zipCode,           // OrderResponse와 같이 V18 이전 주문은 null
        String roadAddress,
        String detailAddress,
        String deliveryRequest,   // 배달 요청사항 (없으면 null)
        OrderStatus status,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        LocalDateTime deliveredAt,
        PaymentSummary payment) {

    public static OrderDetailResponse of(Order order, Payment payment) {
        return new OrderDetailResponse(
                order.getId(),
                order.getStore().getId(),
                order.getStore().getName(),
                order.getOrderItems().stream().map(OrderItemResponse::from).toList(),
                order.getTotalPrice(),
                order.getDeliveryAddress(),
                order.getAddress() == null ? null : order.getAddress().getZipCode(),
                order.getAddress() == null ? null : order.getAddress().getRoadAddress(),
                order.getAddress() == null ? null : order.getAddress().getDetailAddress(),
                order.getDeliveryRequest(),
                order.getStatus(),
                order.getCreatedAt(),
                order.getUpdatedAt(),
                order.getDeliveredAt(),
                payment == null ? null : PaymentSummary.from(payment)
        );
    }

    // 주문에 딸린 결제 — 주문 id·주문 상태는 바깥 필드와 겹치므로 PaymentResponse 대신 결제 자체 값만
    // 수수료·정산액은 포함하지 않음 (CUSTOMER에게 보이면 안 되고, OWNER는 정산 API에서 확인)
    public record PaymentSummary(
            Long id,
            long amount,
            PaymentMethod paymentMethod,
            PaymentStatus status,
            LocalDateTime paidAt,
            LocalDateTime cancelledAt,
            LocalDateTime refundedAt,
            long refundedAmount) { // 누적 환불액 (V21 부분 환불)

        static PaymentSummary from(Payment payment) {
            return new PaymentSummary(
                    payment.getId(),
                    payment.getAmount(),
                    payment.getPaymentMethod(),
                    payment.getStatus(),
                    payment.getPaidAt(),
                    payment.getCancelledAt(),
                    payment.getRefundedAt(),
                    payment.getRefundedAmount()
            );
        }
    }
}
