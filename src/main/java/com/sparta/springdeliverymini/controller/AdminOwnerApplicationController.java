package com.sparta.springdeliverymini.controller;

import com.sparta.springdeliverymini.dto.OwnerApplicationRejectRequest;
import com.sparta.springdeliverymini.dto.OwnerApplicationResponse;
import com.sparta.springdeliverymini.dto.PageQuery;
import com.sparta.springdeliverymini.entity.OwnerApplicationStatus;
import com.sparta.springdeliverymini.service.OwnerApplicationService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

// 관리자 사장님 전환 신청 처리 — ADMIN만 (SecurityConfig의 /api/admin/**, Service에서도 한 번 더 확인)
@RestController
@RequestMapping("/api/admin/owner-applications")
@RequiredArgsConstructor
public class AdminOwnerApplicationController {

    private final OwnerApplicationService ownerApplicationService;

    // 상태별 목록 — 기본은 처리 대상인 REQUESTED, id 오름차순(오래된 신청부터)
    @GetMapping
    public ResponseEntity<List<OwnerApplicationResponse>> getApplications(
            @RequestParam(defaultValue = "REQUESTED") OwnerApplicationStatus status,
            @RequestParam(defaultValue = PageQuery.DEFAULT_PAGE_PARAM) int page,
            @RequestParam(defaultValue = PageQuery.DEFAULT_SIZE_PARAM) int size,
            Authentication authentication) {
        return SliceResponse.of(ownerApplicationService.getApplications(
                status, PageQuery.of(page, size), authentication.getName()));
    }

    // 승인 — 신청자 역할이 OWNER로 바뀜 (대기 중이 아니면 409). 요청 본문 없음
    @PatchMapping("/{id}/approve")
    public OwnerApplicationResponse approve(@PathVariable Long id, Authentication authentication) {
        return ownerApplicationService.approve(id, authentication.getName());
    }

    @PatchMapping("/{id}/reject")
    public OwnerApplicationResponse reject(@PathVariable Long id,
                                           @Valid @RequestBody OwnerApplicationRejectRequest request,
                                           Authentication authentication) {
        return ownerApplicationService.reject(id, request.reason(), authentication.getName());
    }
}
