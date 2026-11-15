package com.sparta.springdeliverymini.controller;

import com.sparta.springdeliverymini.dto.OutboxEventResponse;
import com.sparta.springdeliverymini.dto.OutboxRepublishResponse;
import com.sparta.springdeliverymini.dto.PageQuery;
import com.sparta.springdeliverymini.entity.OutboxEventStatus;
import com.sparta.springdeliverymini.service.AdminOutboxService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

// 관리자 Outbox 관리 — ADMIN만 접근 가능 (SecurityConfig의 /api/admin/**, Service에서도 한 번 더 확인)
@RestController
@RequestMapping("/api/admin/outbox-events")
@RequiredArgsConstructor
public class AdminOutboxController {

    private final AdminOutboxService adminOutboxService;

    // 상태별 이벤트 목록 — 기본은 운영자 확인 대상인 FAILED (?status=FAILED&page=0&size=20)
    @GetMapping
    public ResponseEntity<List<OutboxEventResponse>> getEvents(
            @RequestParam(defaultValue = "FAILED") OutboxEventStatus status,
            @RequestParam(defaultValue = PageQuery.DEFAULT_PAGE_PARAM) int page,
            @RequestParam(defaultValue = PageQuery.DEFAULT_SIZE_PARAM) int size,
            Authentication authentication) {
        return SliceResponse.of(
                adminOutboxService.getEvents(status, PageQuery.of(page, size), authentication.getName()));
    }

    // FAILED 이벤트 1건 재발행 — FAILED → PENDING (FAILED가 아니면 409), 실제 발행은 다음 발행 주기에
    @PostMapping("/{id}/republish")
    public OutboxEventResponse republish(@PathVariable Long id, Authentication authentication) {
        return adminOutboxService.republish(id, authentication.getName());
    }

    // FAILED 이벤트 일괄 재발행 — 오래된 순으로 limit건 (기본·최대 100)
    @PostMapping("/republish")
    public OutboxRepublishResponse republishFailed(
            @RequestParam(defaultValue = "100") int limit, Authentication authentication) {
        return adminOutboxService.republishFailed(limit, authentication.getName());
    }
}
