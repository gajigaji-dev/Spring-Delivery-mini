package com.sparta.springdeliverymini.controller;

import lombok.RequiredArgsConstructor;
import com.sparta.springdeliverymini.dto.SettlementAggregationFailureResponse;
import com.sparta.springdeliverymini.dto.SettlementReaggregationResponse;
import com.sparta.springdeliverymini.dto.SettlementResponse;
import com.sparta.springdeliverymini.dto.SettlementStatusHistoryResponse;
import com.sparta.springdeliverymini.entity.SettlementAggregationFailureStatus;
import com.sparta.springdeliverymini.entity.SettlementStatus;
import com.sparta.springdeliverymini.service.AdminSettlementService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

// 관리자 정산 관리 — ADMIN만 접근 가능 (SecurityConfig의 /api/admin/**, Service에서도 한 번 더 확인)
// 처리자는 어떤 API에서도 요청으로 받지 않음 → 항상 토큰의 인증 정보(authentication.getName())를 Service에 넘김
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

    // 정산 상태 변경 이력 — 누가 언제 집계·확정·지급했는지 (기록 순)
    @GetMapping("/{id}/histories")
    public List<SettlementStatusHistoryResponse> getHistories(@PathVariable Long id, Authentication authentication) {
        return adminSettlementService.getHistories(id, authentication.getName());
    }

    // 누락·실패분 재집계 — date: yyyy-MM-dd (필수), storeId (선택, 없으면 그 날짜의 대상 매장 전체)
    // 정산이 없는 매장·날짜만 새로 집계. storeId를 지정했는데 이미 정산이 있으면 409
    // 예) POST /api/admin/settlements/reaggregate?date=2026-11-05&storeId=1
    @PostMapping("/reaggregate")
    public SettlementReaggregationResponse reaggregate(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(required = false) Long storeId,
            Authentication authentication) {
        return adminSettlementService.reaggregate(date, storeId, authentication.getName());
    }

    // 집계 실패 기록 목록 — status: FAILED(재집계 대상) / RESOLVED (선택)
    // 예) GET /api/admin/settlements/aggregation-failures?status=FAILED
    @GetMapping("/aggregation-failures")
    public List<SettlementAggregationFailureResponse> getAggregationFailures(
            @RequestParam(required = false) SettlementAggregationFailureStatus status,
            Authentication authentication) {
        return adminSettlementService.getAggregationFailures(status, authentication.getName());
    }
}
