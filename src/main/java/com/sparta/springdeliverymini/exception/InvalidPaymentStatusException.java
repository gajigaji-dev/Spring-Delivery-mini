package com.sparta.springdeliverymini.exception;

// 현재 결제 상태에서 허용되지 않는 취소·환불을 시도할 때 Payment 엔티티가 던지는 예외
// (예: 이미 환불된 결제를 다시 환불, 배달 전 결제를 환불)
// InvalidOrderStatusException과 같은 이유로 ApiException과 분리하고, 409 변환은 GlobalExceptionHandler에서 처리
public class InvalidPaymentStatusException extends RuntimeException {

    public InvalidPaymentStatusException(String message) {
        super(message);
    }
}
