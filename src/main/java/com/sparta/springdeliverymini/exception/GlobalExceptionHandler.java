package com.sparta.springdeliverymini.exception;

import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

@Slf4j // Lombok: log 필드 자동 생성
@RestControllerAdvice
public class GlobalExceptionHandler {

    // 409 아이디 중복, 401 로그인 실패 등 서비스에서 상태코드를 지정해 던지는 예외
    @ExceptionHandler(ApiException.class)
    public ResponseEntity<Map<String, String>> handleApiException(ApiException e) {
        return ResponseEntity.status(e.getStatus()).body(Map.of("message", e.getMessage()));
    }

    // Order 엔티티의 상태 전이 규칙 위반 (예: 결제 전 주문 수락, 결제완료 주문 취소) → 409
    @ExceptionHandler(InvalidOrderStatusException.class)
    public ResponseEntity<Map<String, String>> handleInvalidOrderStatus(InvalidOrderStatusException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("message", e.getMessage()));
    }

    // Service에서 따로 처리하지 못한 DB 제약 조건 위반 (NOT NULL, UNIQUE, FK 등)
    // 어느 API에서든 발생할 수 있으므로 특정 상황(예: 아이디 중복)을 가정한 메시지를 쓰지 않음
    // 아이디 중복·중복 결제처럼 예상 가능한 경우는 UserService, PaymentService에서 직접 잡아 구체적인 메시지로 응답
    // 원인 파악을 위해 실제 DB 에러는 서버 로그에 남김
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<Map<String, String>> handleDataIntegrity(DataIntegrityViolationException e) {
        log.error("DB 제약 조건 위반", e);
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(Map.of("message", "데이터 저장 중 제약 조건을 위반했습니다."));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, String>> handleValidation(MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .findFirst()
                .orElse("잘못된 요청입니다.");
        return ResponseEntity.badRequest().body(Map.of("message", message));
    }

    // JSON 문법 오류, 타입 불일치, enum에 없는 값이 들어오는 등 본문을 읽을 수 없는 경우
    // 예) 회원가입 role에 "ADMIN", 주문 상태 변경 status에 "FOO", 숫자 필드에 문자열
    // 여러 API에서 공통으로 발생하므로 특정 필드를 언급하지 않는 메시지 사용
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, String>> handleNotReadable(HttpMessageNotReadableException e) {
        return ResponseEntity.badRequest()
                .body(Map.of("message", "요청 본문 형식이 올바르지 않습니다. 값의 형식과 허용된 값을 확인해주세요."));
    }
}
