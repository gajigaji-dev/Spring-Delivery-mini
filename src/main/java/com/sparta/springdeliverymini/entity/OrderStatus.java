package com.sparta.springdeliverymini.entity;

public enum OrderStatus {
    ORDER_REQUEST,      // 주문요청 — 주문 생성 시 항상 이 상태로 시작
    ORDER_CANCELLED,    // 주문취소 — 주문요청·결제완료 상태에서만 변경 가능 (결제완료면 결제도 취소)
    PAYMENT_COMPLETED,  // 결제완료 — CUSTOMER가 결제를 마친 상태
    ORDER_ACCEPTED,     // 주문수락 — 결제완료 상태에서만 OWNER가 변경 가능
    DELIVERY_COMPLETED  // 배달완료 — 주문수락 상태에서만 OWNER가 변경 가능 (이후 변경 불가)
}
