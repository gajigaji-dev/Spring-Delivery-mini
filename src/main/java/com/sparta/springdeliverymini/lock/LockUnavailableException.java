package com.sparta.springdeliverymini.lock;

// Redis 장애·타임아웃으로 락을 잡았는지 알 수 없을 때 — 호출한 쪽은 작업을 실행하지 않아야 함
// DataAccessException을 그대로 던지지 않는 이유: 작업(task) 안의 DB 오류와 구분해, "락 단계 실패"만 골라 처리하기 위함
public class LockUnavailableException extends RuntimeException {

    public LockUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
