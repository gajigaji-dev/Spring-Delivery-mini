package com.sparta.springdeliverymini.dto;

import com.sparta.springdeliverymini.entity.Notification;
import com.sparta.springdeliverymini.entity.NotificationSendStatus;
import com.sparta.springdeliverymini.entity.OutboxEventType;

import java.time.LocalDateTime;

// 내 알림 목록 응답 — 받는 사람 본인에게만 보이므로 받는 사람 id는 넣지 않음
// read·readAt(V15): 읽음 여부·처음 읽은 시각 (안 읽었으면 false·null) — 필드 추가만, 기존 필드 유지
public record NotificationResponse(
        Long id,
        OutboxEventType eventType,
        Long orderId,
        String message,
        LocalDateTime createdAt,
        NotificationSendStatus sendStatus,
        LocalDateTime sentAt,
        boolean read,
        LocalDateTime readAt
) {
    public static NotificationResponse from(Notification notification) {
        return new NotificationResponse(
                notification.getId(),
                notification.getEventType(),
                notification.getOrderId(),
                notification.getMessage(),
                notification.getCreatedAt(),
                notification.getSendStatus(),
                notification.getSentAt(),
                notification.isRead(),
                notification.getReadAt()
        );
    }
}
