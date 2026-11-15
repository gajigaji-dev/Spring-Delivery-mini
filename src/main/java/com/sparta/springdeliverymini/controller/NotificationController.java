package com.sparta.springdeliverymini.controller;

import com.sparta.springdeliverymini.dto.NotificationResponse;
import com.sparta.springdeliverymini.dto.PageQuery;
import com.sparta.springdeliverymini.service.NotificationService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
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
}
