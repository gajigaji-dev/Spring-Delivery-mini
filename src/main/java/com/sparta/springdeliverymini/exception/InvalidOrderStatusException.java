package com.sparta.springdeliverymini.exception;

// 현재 주문 상태에서 허용되지 않는 상태 변경을 시도할 때 Order 엔티티가 던지는 예외
// 엔티티가 HttpStatus(웹 계층)를 알지 않도록 ApiException과 분리하고,
// 409 응답 변환은 GlobalExceptionHandler에서 처리
// code: 같은 409 안에서 화면 안내를 나누기 위한 구분 값 (ErrorCode) — 지정하지 않으면 INVALID_ORDER_STATUS
public class InvalidOrderStatusException extends RuntimeException {

    private final ErrorCode code;

    public InvalidOrderStatusException(String message) {
        this(ErrorCode.INVALID_ORDER_STATUS, message);
    }

    public InvalidOrderStatusException(ErrorCode code, String message) {
        super(message);
        this.code = code;
    }

    public ErrorCode getCode() {
        return code;
    }
}
