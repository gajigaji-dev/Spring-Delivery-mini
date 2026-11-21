package com.sparta.springdeliverymini.exception;

// 배달 요청사항 형식 위반 (100자 초과, 줄바꿈 등 제어 문자)
// Order 생성자가 던지고, 400 + code INVALID_DELIVERY_REQUEST 변환은 GlobalExceptionHandler에서 처리 (InvalidAddressException과 같은 방식)
public class InvalidDeliveryRequestException extends RuntimeException {

    public InvalidDeliveryRequestException(String message) {
        super(message);
    }
}
