package com.sparta.springdeliverymini.controller;

import com.sparta.springdeliverymini.exception.ApiException;
import com.sparta.springdeliverymini.pg.FakeTossPaymentsClient;
import com.sparta.springdeliverymini.pg.FakeTossPaymentsClient.Scenario;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// 가짜 결제창의 "인증" 단계 (toss.payments.mode=fake일 때만 등록, 아니면 404) — CUSTOMER만 (SecurityConfig POST /api/payments/**)
// 실제 토스에서는 결제창이 카드 인증 후 결제 키를 발급하고 successUrl로 보냄. 가짜 모드에서는 화면(/payments/fake-toss)이 이 API로 키를 받음
// 금액은 결제창에 넘긴 값 그대로 기록 → 승인 때 서버가 주문 총액과 대조하는 흐름은 실제와 같음
@RestController
@RequestMapping("/api/payments/fake-toss")
@RequiredArgsConstructor
@ConditionalOnProperty(name = "toss.payments.mode", havingValue = "fake")
public class FakeTossCheckoutController {

    private final FakeTossPaymentsClient fakeTossPaymentsClient;

    public record AuthorizeRequest(@NotBlank String tossOrderId, @NotNull @Positive Long amount, Scenario scenario) {
    }

    public record AuthorizeResponse(String paymentKey) {
    }

    @PostMapping("/authorize")
    public AuthorizeResponse authorize(@Valid @RequestBody AuthorizeRequest request) {
        try {
            return new AuthorizeResponse(
                    fakeTossPaymentsClient.authorize(request.tossOrderId(), request.amount(), request.scenario()));
        } catch (IllegalArgumentException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }
}
