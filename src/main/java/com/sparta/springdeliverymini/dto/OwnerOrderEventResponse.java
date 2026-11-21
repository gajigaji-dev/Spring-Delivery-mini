package com.sparta.springdeliverymini.dto;

import com.sparta.springdeliverymini.entity.OutboxEventType;

import java.time.LocalDateTime;

// 사장님 실시간 주문 알림(SSE, #76)으로 보내는 본문 — 알림 기록(notifications)에 저장한 사장님 알림 1건
// - 화면은 이 값으로 토스트만 띄우고, 주문 목록은 API로 다시 받음 (이 본문을 목록의 기준으로 쓰지 않음)
//   → 이벤트가 빠지거나 순서가 바뀌어도 화면 데이터는 항상 서버 조회 결과
// - type: PAYMENT_COMPLETED(새 주문) / PAYMENT_REFUNDED(고객 취소로 환불)
public record OwnerOrderEventResponse(
        Long notificationId,
        OutboxEventType type,
        Long orderId,
        Long storeId,
        long amount,
        String message,
        LocalDateTime occurredAt
) {
}
