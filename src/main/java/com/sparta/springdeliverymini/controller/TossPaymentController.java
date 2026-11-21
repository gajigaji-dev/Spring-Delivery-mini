package com.sparta.springdeliverymini.controller;

import com.sparta.springdeliverymini.dto.PaymentResponse;
import com.sparta.springdeliverymini.dto.TossCheckoutRequest;
import com.sparta.springdeliverymini.dto.TossCheckoutResponse;
import com.sparta.springdeliverymini.dto.TossConfigResponse;
import com.sparta.springdeliverymini.dto.TossConfirmRequest;
import com.sparta.springdeliverymini.service.PaymentIdempotencyService;
import com.sparta.springdeliverymini.service.PaymentIdempotencyService.PaymentResult;
import com.sparta.springdeliverymini.service.TossPaymentService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// 토스페이먼츠 테스트 모드 결제 (V20, docs/api.md 토스페이먼츠 결제 장) — CUSTOMER만 (SecurityConfig)
// 흐름: config(사용 여부) → checkout(결제창 값) → 브라우저가 결제창 → successUrl → confirm(서버 승인)
@RestController
@RequestMapping("/api/payments/toss")
@RequiredArgsConstructor
public class TossPaymentController {

    private final TossPaymentService tossPaymentService;
    private final PaymentIdempotencyService paymentIdempotencyService;

    // 토스 결제 사용 여부·클라이언트 키 — 키가 없으면 enabled=false (화면은 모의 결제 버튼)
    @GetMapping("/config")
    public TossConfigResponse config() {
        return tossPaymentService.config();
    }

    // 결제창에 넘길 값(주문번호·금액·주문 이름) — 저장하지 않음, 결제할 수 없는 주문이면 409
    @PostMapping("/checkout")
    public TossCheckoutResponse checkout(@Valid @RequestBody TossCheckoutRequest request,
                                         Authentication authentication) {
        return tossPaymentService.checkout(request.orderId(), authentication.getName());
    }

    // 결제 승인 — 성공 시 201 + 결제 내역 (모의 결제 POST /api/payments와 같은 응답)
    // Idempotency-Key(선택): 화면은 토스 주문번호를 키로 보냄 → 같은 결제의 재시도는 처음 응답(헤더 Idempotent-Replayed)
    @PostMapping("/confirm")
    public ResponseEntity<PaymentResponse> confirm(
            @Valid @RequestBody TossConfirmRequest request,
            @RequestHeader(name = PaymentController.IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            Authentication authentication) {
        PaymentResult result = paymentIdempotencyService.confirmToss(request, authentication.getName(), idempotencyKey);
        ResponseEntity.BodyBuilder response = ResponseEntity.status(HttpStatus.CREATED);
        if (result.replayed()) {
            response.header(PaymentController.REPLAYED_HEADER, "true");
        }
        return response.body(result.response());
    }
}
