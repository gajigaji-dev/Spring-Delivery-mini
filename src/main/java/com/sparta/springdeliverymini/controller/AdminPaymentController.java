package com.sparta.springdeliverymini.controller;

import lombok.RequiredArgsConstructor;
import com.sparta.springdeliverymini.dto.PaymentResponse;
import com.sparta.springdeliverymini.service.AdminPaymentService;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

// 관리자 결제 관리 — ADMIN만 접근 가능 (SecurityConfig의 /api/admin/**, Service에서도 한 번 더 확인)
@RestController
@RequestMapping("/api/admin/payments")
@RequiredArgsConstructor
public class AdminPaymentController {

    private final AdminPaymentService adminPaymentService;

    // 배달완료 주문 전액 환불 — COMPLETED → REFUNDED (요청 본문 없음, 환불 시각은 서버 시각)
    // 배달 전 주문은 고객이 주문 취소(PATCH /api/orders/{id}/cancel)로 처리
    @PatchMapping("/{id}/refund")
    public PaymentResponse refund(@PathVariable Long id, Authentication authentication) {
        return adminPaymentService.refund(id, authentication.getName());
    }
}
