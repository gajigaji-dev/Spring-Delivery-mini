package com.sparta.springdeliverymini.controller;

import com.sparta.springdeliverymini.dto.NotificationResponse;
import com.sparta.springdeliverymini.dto.PageQuery;
import com.sparta.springdeliverymini.dto.UnreadNotificationCountResponse;
import com.sparta.springdeliverymini.service.NotificationService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/notifications")
@RequiredArgsConstructor
public class NotificationController {

    private final NotificationService notificationService;

    // 내 알림 목록 — 로그인한 사용자 본인이 받는 알림만 (역할 무관, 다른 사람의 알림을 조회하는 경로는 없음)
    // 페이지 단위 (?page=0&size=20, size 최대 100), 본문은 배열 그대로이고 페이지 정보는 응답 헤더
    @GetMapping
    public ResponseEntity<List<NotificationResponse>> getMyNotifications(
            @RequestParam(defaultValue = PageQuery.DEFAULT_PAGE_PARAM) int page,
            @RequestParam(defaultValue = PageQuery.DEFAULT_SIZE_PARAM) int size,
            Authentication authentication) {
        return SliceResponse.of(
                notificationService.getMyNotifications(authentication.getName(), PageQuery.of(page, size)));
    }

    // 안 읽은 알림 개수 (V15) — 알림 배지용
    @GetMapping("/unread-count")
    public UnreadNotificationCountResponse getMyUnreadCount(Authentication authentication) {
        return new UnreadNotificationCountResponse(notificationService.countMyUnread(authentication.getName()));
    }

    // 읽음 처리 (V15) — 본인 알림만, 이미 읽은 알림도 204 (멱등: 재시도·중복 클릭에 오류를 내지 않음)
    @PatchMapping("/{id}/read")
    public ResponseEntity<Void> markAsRead(@PathVariable Long id, Authentication authentication) {
        notificationService.markAsRead(authentication.getName(), id);
        return ResponseEntity.noContent().build();
    }
}
