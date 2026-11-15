package com.sparta.springdeliverymini.event;

// 형식이 잘못된 Kafka 메시지 (JSON 오류·필수 값 누락·모르는 이벤트 종류)
// 재시도해도 결과가 같으므로 KafkaConfig의 오류 처리기가 재시도 없이 바로 DLT로 보냄
public class InvalidEventMessageException extends RuntimeException {

    public InvalidEventMessageException(String message) {
        super(message);
    }

    public InvalidEventMessageException(String message, Throwable cause) {
        super(message, cause);
    }
}
