package com.sparta.springdeliverymini.exception;

// 현재 정산 상태에서 허용되지 않는 상태 변경을 시도할 때 Settlement 엔티티가 던지는 예외
// (예: 확정 대기 정산을 바로 지급, 이미 지급된 정산을 다시 지급)
// InvalidOrderStatusException과 같은 이유로 ApiException과 분리하고, 409 변환은 GlobalExceptionHandler에서 처리
public class InvalidSettlementStatusException extends RuntimeException {

    public InvalidSettlementStatusException(String message) {
        super(message);
    }
}
