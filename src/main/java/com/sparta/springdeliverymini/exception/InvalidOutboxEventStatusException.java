package com.sparta.springdeliverymini.exception;

// OutboxEvent의 상태 전이 규칙 위반 (예: FAILED가 아닌 이벤트 재발행 요청) → 409
// GlobalExceptionHandler에서 처리
public class InvalidOutboxEventStatusException extends RuntimeException {

    public InvalidOutboxEventStatusException(String message) {
        super(message);
    }
}
