package com.sparta.springdeliverymini.controller;

import lombok.RequiredArgsConstructor;
import com.sparta.springdeliverymini.dto.SettlementResponse;
import com.sparta.springdeliverymini.entity.SettlementStatus;
import com.sparta.springdeliverymini.service.AdminSettlementService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

// 관리자 정산 관리 — ADMIN만 접근 가능 (SecurityConfig의 /api/admin/**, Service에서도 한 번 더 확인)
@RestController
@RequestMapping("/api/admin/settlements")
@RequiredArgsConstructor
public class AdminSettlementController {

    private final AdminSettlementService adminSettlementService;

    // 날짜별 정산 목록 — date: yyyy-MM-dd (필수), status: PENDING / CONFIRMED / PAID (선택)
    // 예) GET /api/admin/settlements?date=2026-11-05&status=PENDING
    @GetMapping
    public List<SettlementResponse> getSettlements(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(required = false) SettlementStatus status,
            Authentication authentication) {
        return adminSettlementService.getSettlements(date, status, authentication.getName());
    }

    // 정산 확정 — PENDING → CONFIRMED (요청 본문 없음, 확정 시각은 서버 시각)
    @PatchMapping("/{id}/confirm")
    public SettlementResponse confirm(@PathVariable Long id, Authentication authentication) {
        return adminSettlementService.confirm(id, authentication.getName());
    }

    // 정산 지급 — CONFIRMED → PAID (요청 본문 없음, 지급 시각은 서버 시각)
    @PatchMapping("/{id}/pay")
    public SettlementResponse pay(@PathVariable Long id, Authentication authentication) {
        return adminSettlementService.pay(id, authentication.getName());
    }
}
