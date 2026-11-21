package com.sparta.springdeliverymini.exception;

// 사장님 전환 신청의 상태 전이 규칙 위반 (이미 처리된 신청을 다시 승인·거절, CUSTOMER가 아닌 회원을 OWNER로 전환)
// 다른 Invalid*StatusException과 같이 엔티티가 던지고, 409 변환은 GlobalExceptionHandler에서 처리
public class InvalidOwnerApplicationStatusException extends RuntimeException {

    public InvalidOwnerApplicationStatusException(String message) {
        super(message);
    }
}
