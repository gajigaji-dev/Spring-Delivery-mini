package com.sparta.springdeliverymini.exception;

// 현재 결제 상태에서 허용되지 않는 취소·환불을 시도할 때 Payment 엔티티가 던지는 예외
// (예: 이미 환불된 결제를 다시 환불, 배달 전 결제를 환불)
// InvalidOrderStatusException과 같은 이유로 ApiException과 분리하고, 409 변환은 GlobalExceptionHandler에서 처리
// code: 같은 409 안에서 화면 안내를 나누기 위한 구분 값 (ErrorCode) — 지정하지 않으면 INVALID_PAYMENT_STATUS
public class InvalidPaymentStatusException extends RuntimeException {

    private final ErrorCode code;

    public InvalidPaymentStatusException(String message) {
        this(ErrorCode.INVALID_PAYMENT_STATUS, message);
    }

    public InvalidPaymentStatusException(ErrorCode code, String message) {
        super(message);
        this.code = code;
    }

    public ErrorCode getCode() {
        return code;
    }
}
