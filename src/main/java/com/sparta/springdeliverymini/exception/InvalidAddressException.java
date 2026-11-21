package com.sparta.springdeliverymini.exception;

// 배송 주소 형식 위반 (우편번호 5자리 아님, 도로명 주소 없음, 길이 초과, 줄바꿈 등 제어 문자)
// 엔티티 값 객체(DeliveryAddress)가 던지고, 400 + code INVALID_ADDRESS 변환은 GlobalExceptionHandler에서 처리
// 요청 DTO의 Bean Validation이 아니라 값 객체에서 검사하는 이유: 주문을 만드는 경로가 늘어도 같은 규칙이 적용되도록
public class InvalidAddressException extends RuntimeException {

    public InvalidAddressException(String message) {
        super(message);
    }
}
