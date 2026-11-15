package com.sparta.springdeliverymini.dto;

import com.sparta.springdeliverymini.entity.Notification;
import com.sparta.springdeliverymini.entity.NotificationSendStatus;
import com.sparta.springdeliverymini.entity.OutboxEventType;

import java.time.LocalDateTime;

// 내 알림 목록 응답 — 받는 사람 본인에게만 보이므로 받는 사람 id는 넣지 않음
public record NotificationResponse(
        Long id,
        OutboxEventType eventType,
        Long orderId,
        String message,
        LocalDateTime createdAt,
        NotificationSendStatus sendStatus,
        LocalDateTime sentAt
) {
    public static NotificationResponse from(Notification notification) {
        return new NotificationResponse(
                notification.getId(),
                notification.getEventType(),
                notification.getOrderId(),
                notification.getMessage(),
                notification.getCreatedAt(),
                notification.getSendStatus(),
                notification.getSentAt()
        );
    }
}
