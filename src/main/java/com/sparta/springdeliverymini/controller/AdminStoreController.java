package com.sparta.springdeliverymini.controller;

import lombok.RequiredArgsConstructor;
import com.sparta.springdeliverymini.dto.AdminStoreFeeRateResponse;
import com.sparta.springdeliverymini.dto.StoreFeeRateRequest;
import com.sparta.springdeliverymini.service.AdminStoreService;
import jakarta.validation.Valid;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

// 관리자 매장 관리 — ADMIN만 접근 가능 (SecurityConfig의 /api/admin/**, Service에서도 한 번 더 확인)
@RestController
@RequestMapping("/api/admin/stores")
@RequiredArgsConstructor
public class AdminStoreController {

    private final AdminStoreService adminStoreService;

    // 매장 수수료율 설정 — 예) {"feeRate": 0.03} (3%), 이후 결제부터 적용
    @PutMapping("/{id}/fee-rate")
    public AdminStoreFeeRateResponse changeFeeRate(
            @PathVariable Long id,
            @Valid @RequestBody StoreFeeRateRequest request,
            Authentication authentication) {
        return adminStoreService.changeFeeRate(id, request.feeRate(), authentication.getName());
    }

    // 매장 수수료율 해제 — 이후 결제는 공통 수수료율 사용 (요청 본문 없음)
    @DeleteMapping("/{id}/fee-rate")
    public AdminStoreFeeRateResponse clearFeeRate(
            @PathVariable Long id,
            Authentication authentication) {
        return adminStoreService.clearFeeRate(id, authentication.getName());
    }
}
