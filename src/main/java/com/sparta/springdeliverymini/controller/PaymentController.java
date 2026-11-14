package com.sparta.springdeliverymini.controller;

import lombok.RequiredArgsConstructor;
import com.sparta.springdeliverymini.dto.PaymentRequest;
import com.sparta.springdeliverymini.dto.PaymentResponse;
import com.sparta.springdeliverymini.service.PaymentIdempotencyService;
import com.sparta.springdeliverymini.service.PaymentIdempotencyService.PaymentResult;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// @Controller는 반환값을 뷰 이름으로 해석하므로 JSON 응답을 위해 @RestController 사용
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor // final 필드를 받는 생성자 자동 생성 (생성자 주입)
public class PaymentController {

    static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";
    static final String REPLAYED_HEADER = "Idempotent-Replayed";

    private final PaymentIdempotencyService paymentIdempotencyService;

    // CUSTOMER만 결제 가능
    // CUSTOMER 권한 확인은 SecurityConfig에서 토큰의 role로 처리 (OWNER → 403)
    // 본인 주문 여부·결제 수단·주문 상태 검증은 Service에서 처리
    // Idempotency-Key(선택, #41): 있으면 같은 키의 재시도에 처음 응답을 그대로 돌려줌 (없으면 기존 동작)
    @PostMapping("/payments")
    public ResponseEntity<PaymentResponse> pay(
            @Valid @RequestBody PaymentRequest request,
            @RequestHeader(name = IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            Authentication authentication) {

        // 결제자는 토큰에서 꺼낸 아이디로 Service에서 조회
        PaymentResult result = paymentIdempotencyService.pay(request, authentication.getName(), idempotencyKey);

        // 결제 성공 시 201 Created와 결제 내역 반환 — 재전송도 처음과 같은 201·본문 (재전송 여부만 헤더로 알림)
        ResponseEntity.BodyBuilder response = ResponseEntity.status(HttpStatus.CREATED);
        if (result.replayed()) {
            response.header(REPLAYED_HEADER, "true");
        }
        return response.body(result.response());
    }
}
