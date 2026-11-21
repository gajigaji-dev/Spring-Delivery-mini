package com.sparta.springdeliverymini.pg;

import com.sparta.springdeliverymini.config.TossPaymentsProperties;

// 토스페이먼츠 코어 API (승인·조회·취소) — 구현은 설정 toss.payments.mode로 하나만 빈 등록
//   real(기본): TossPaymentsHttpClient — 토스 테스트 서버(api.tosspayments.com)를 테스트 키로 호출
//   fake      : FakeTossPaymentsClient — 토스에 접속하지 않고 메모리에서 흉내 (오프라인 데모·장애 재현)
//
// 예외 약속 (구현 모두 같음)
//   TossApiException          : 토스가 처리하고 명확히 거절 → 결과 확정
//   TossUnknownResultException: 처리됐는지 모름 → 조회로 확인하거나 재시도 (실패로 단정하지 않음)
public interface TossPaymentsClient {

    boolean enabled();

    // 브라우저 결제창용 공개 키 (fake면 의미 없는 고정 값)
    String clientKey();

    TossPaymentsProperties.Mode mode();

    TossPayment confirm(String paymentKey, String orderId, long amount, String idempotencyKey);

    TossPayment getPayment(String paymentKey);

    // cancelAmount: null이면 전액 취소, 값이 있으면 그 금액만 부분 취소
    TossPayment cancel(String paymentKey, String cancelReason, Long cancelAmount, String idempotencyKey);
}
