package com.sparta.springdeliverymini.dto;

// 결제창(requestPayment)에 그대로 넘길 값 — 금액·주문번호를 서버가 정함 (화면이 금액을 계산하지 않음)
// tossOrderId: order-{주문 id}-{난수 16자리} — 공개 테스트 키는 여러 개발자가 함께 쓰므로 주문 id만으로는 겹칠 수 있어 난수를 붙임
//   DB에 저장하지 않음: 승인 때 형식과 주문 id만 확인하고, 금액은 주문 총액과 다시 대조 (서버가 정한 값만 승인됨)
// customerKey: 회원 id를 해시한 값 (토스 규칙 22~50자) — 아이디·이메일 등 개인정보를 토스에 보내지 않음
public record TossCheckoutResponse(String clientKey, String customerKey, String tossOrderId, long amount,
                                   String orderName) {
}
