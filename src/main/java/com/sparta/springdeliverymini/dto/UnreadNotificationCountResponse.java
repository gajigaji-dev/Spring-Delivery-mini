package com.sparta.springdeliverymini.dto;

// 안 읽은 알림 개수 — 객체로 감싸 나중에 필드를 추가해도 응답 형식이 깨지지 않도록
public record UnreadNotificationCountResponse(long unreadCount) {
}
