package com.sparta.springdeliverymini.exception;

import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

// 모든 예외를 ApiErrorResponse 형식으로 변환 (message 문구·상태 코드는 기존과 동일, status·error·path·traceId 추가)
// 로그 정책
// - 4xx: 클라이언트 요청 문제라 스택 트레이스 없이 WARN 한 줄 (traceId로 요청 로그와 연결)
// - 5xx: 원인 파악이 필요하므로 스택 트레이스와 함께 ERROR, 응답에는 내부 메시지를 노출하지 않음
// - 요청 본문·파라미터 값은 로그에 남기지 않음 (비밀번호·결제 정보가 섞일 수 있어 필드명·메시지만 기록)
@Slf4j // Lombok: log 필드 자동 생성
@RestControllerAdvice
public class GlobalExceptionHandler {

    // 409 아이디 중복, 401 로그인 실패 등 서비스에서 상태코드를 지정해 던지는 예외
    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ApiErrorResponse> handleApiException(ApiException e, HttpServletRequest request) {
        log.warn("요청 실패 status={} code={} message={}", e.getStatus().value(), e.getCode(), e.getMessage());
        return jsonError(e.getStatus())
                .body(ApiErrorResponse.of(e.getStatus(), e.getCode(), e.getMessage(), request.getRequestURI()));
    }

    // Order 엔티티의 상태 전이 규칙 위반 (예: 결제 전 주문 수락, 결제완료 주문 취소) → 409
    // code로 이미 결제됨·이미 취소됨·취소 불가를 구분 (#77) — 화면 안내가 다름
    @ExceptionHandler(InvalidOrderStatusException.class)
    public ResponseEntity<ApiErrorResponse> handleInvalidOrderStatus(InvalidOrderStatusException e,
                                                                     HttpServletRequest request) {
        return clientError(HttpStatus.CONFLICT, e.getCode(), e.getMessage(), request);
    }

    // 배달 요청사항 형식 위반 (Order, #77 후속) → 400
    @ExceptionHandler(InvalidDeliveryRequestException.class)
    public ResponseEntity<ApiErrorResponse> handleInvalidDeliveryRequest(InvalidDeliveryRequestException e,
                                                                         HttpServletRequest request) {
        return clientError(HttpStatus.BAD_REQUEST, ErrorCode.INVALID_DELIVERY_REQUEST, e.getMessage(), request);
    }

    // 배송 주소 형식 위반 (DeliveryAddress, #77) → 400
    @ExceptionHandler(InvalidAddressException.class)
    public ResponseEntity<ApiErrorResponse> handleInvalidAddress(InvalidAddressException e,
                                                                 HttpServletRequest request) {
        return clientError(HttpStatus.BAD_REQUEST, ErrorCode.INVALID_ADDRESS, e.getMessage(), request);
    }

    // Settlement 엔티티의 상태 전이 규칙 위반 (예: 확정 전 지급, 이미 지급된 정산 재지급) → 409
    @ExceptionHandler(InvalidSettlementStatusException.class)
    public ResponseEntity<ApiErrorResponse> handleInvalidSettlementStatus(InvalidSettlementStatusException e,
                                                                          HttpServletRequest request) {
        return clientError(HttpStatus.CONFLICT, e.getMessage(), request);
    }

    // Payment 엔티티의 취소·환불 규칙 위반 (예: 중복 환불, 배달 전 결제 환불) → 409
    @ExceptionHandler(InvalidPaymentStatusException.class)
    public ResponseEntity<ApiErrorResponse> handleInvalidPaymentStatus(InvalidPaymentStatusException e,
                                                                       HttpServletRequest request) {
        return clientError(HttpStatus.CONFLICT, e.getCode(), e.getMessage(), request);
    }

    // OutboxEvent 엔티티의 상태 전이 규칙 위반 (예: FAILED가 아닌 이벤트 재발행) → 409
    @ExceptionHandler(InvalidOutboxEventStatusException.class)
    public ResponseEntity<ApiErrorResponse> handleInvalidOutboxEventStatus(InvalidOutboxEventStatusException e,
                                                                           HttpServletRequest request) {
        return clientError(HttpStatus.CONFLICT, e.getMessage(), request);
    }

    // 사장님 전환 신청의 상태 전이 규칙 위반 (예: 이미 처리된 신청 재승인, 고객이 아닌 회원 전환) → 409
    @ExceptionHandler(InvalidOwnerApplicationStatusException.class)
    public ResponseEntity<ApiErrorResponse> handleInvalidOwnerApplicationStatus(
            InvalidOwnerApplicationStatusException e, HttpServletRequest request) {
        return clientError(HttpStatus.CONFLICT, e.getMessage(), request);
    }

    // Service에서 따로 처리하지 못한 DB 제약 조건 위반 (NOT NULL, UNIQUE, FK 등)
    // 어느 API에서든 발생할 수 있으므로 특정 상황(예: 아이디 중복)을 가정한 메시지를 쓰지 않음
    // 아이디 중복·중복 결제처럼 예상 가능한 경우는 UserService, PaymentService에서 직접 잡아 구체적인 메시지로 응답
    // 로그에는 제약 조건 이름만 남김: DB 에러 상세(Detail: Key (username)=(...))에 실제 입력값이 들어 있어
    // 예외 메시지·스택 트레이스를 그대로 남기면 개인정보가 로그에 노출됨
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiErrorResponse> handleDataIntegrity(DataIntegrityViolationException e,
                                                                HttpServletRequest request) {
        String constraint = (e.getCause() instanceof ConstraintViolationException cve)
                ? cve.getConstraintName() : "unknown";
        log.error("DB 제약 조건 위반 constraint={}", constraint);
        return jsonError(HttpStatus.CONFLICT)
                .body(ApiErrorResponse.of(HttpStatus.CONFLICT, "데이터 저장 중 제약 조건을 위반했습니다.",
                        request.getRequestURI()));
    }

    // DB가 제때 응답하지 못한 경우 → 503 (요청은 반영되지 않았고 잠시 후 다시 시도하면 되는 상황이라 500과 구분)
    // - PessimisticLockingFailureException: 행 잠금 대기가 lock_timeout을 넘김, 교착 상태로 이 트랜잭션이 취소됨
    // - QueryTimeoutException: 문장 실행이 statement_timeout을 넘김
    // - CannotCreateTransactionException: 커넥션 풀이 바닥나 connection-timeout 안에 커넥션을 얻지 못함, DB 접속 불가
    // 타임아웃 값은 application.yml spring.datasource.hikari. 스택 트레이스 없이 종류만 남김 (SQL·값이 로그에 남지 않도록)
    @ExceptionHandler({PessimisticLockingFailureException.class, QueryTimeoutException.class,
            CannotCreateTransactionException.class})
    public ResponseEntity<ApiErrorResponse> handleDatabaseBusy(Exception e, HttpServletRequest request) {
        log.error("DB 응답 지연·사용 불가 {} {} cause={}", request.getMethod(), request.getRequestURI(),
                e.getClass().getSimpleName());
        return jsonError(HttpStatus.SERVICE_UNAVAILABLE)
                .body(ApiErrorResponse.of(HttpStatus.SERVICE_UNAVAILABLE,
                        "일시적으로 요청을 처리할 수 없습니다. 잠시 후 다시 시도해 주세요.", request.getRequestURI()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiErrorResponse> handleValidation(MethodArgumentNotValidException e,
                                                             HttpServletRequest request) {
        // 거부된 값(rejectedValue)은 쓰지 않고 필드명 + 검증 메시지만 사용 (비밀번호 등 입력값 노출 방지)
        String message = e.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .findFirst()
                .orElse("잘못된 요청입니다.");
        return clientError(HttpStatus.BAD_REQUEST, message, request);
    }

    // 필수 쿼리 파라미터 누락 (예: 정산 조회의 from, to) → 400
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ApiErrorResponse> handleMissingParameter(MissingServletRequestParameterException e,
                                                                   HttpServletRequest request) {
        return clientError(HttpStatus.BAD_REQUEST, e.getParameterName() + ": 필수 값입니다.", request);
    }

    // 경로·쿼리 파라미터의 형식이 맞지 않음 (예: 날짜에 "2026-13-01", id에 문자열) → 400
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiErrorResponse> handleTypeMismatch(MethodArgumentTypeMismatchException e,
                                                               HttpServletRequest request) {
        return clientError(HttpStatus.BAD_REQUEST, e.getName() + ": 형식이 올바르지 않습니다.", request);
    }

    // JSON 문법 오류, 타입 불일치, enum에 없는 값이 들어오는 등 본문을 읽을 수 없는 경우
    // 예) 회원가입 role에 "ADMIN", 주문 상태 변경 status에 "FOO", 숫자 필드에 문자열
    // 여러 API에서 공통으로 발생하므로 특정 필드를 언급하지 않는 메시지 사용
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiErrorResponse> handleNotReadable(HttpMessageNotReadableException e,
                                                              HttpServletRequest request) {
        return clientError(HttpStatus.BAD_REQUEST,
                "요청 본문 형식이 올바르지 않습니다. 값의 형식과 허용된 값을 확인해주세요.", request);
    }

    // 클라이언트가 이미 끊은 비동기 응답(SSE 등, #76)에 쓰려다 난 예외 — 응답을 쓸 곳이 없으므로 본문 없이 끝냄
    // 아래 Exception 처리기로 가면 500 오류 로그가 쌓이고, 끊긴 응답에 JSON을 쓰려다 예외가 한 번 더 남
    @ExceptionHandler(AsyncRequestNotUsableException.class)
    public void handleAsyncRequestNotUsable(AsyncRequestNotUsableException e) {
        log.debug("이미 끊긴 비동기 응답 message={}", e.getMessage());
    }

    // 위에서 처리하지 않은 나머지 예외
    // - Spring MVC 표준 예외(없는 경로 404, 지원하지 않는 메서드 405 등)는 ErrorResponse로 상태 코드를 알려줌
    //   → 그 상태 코드를 유지하고 형식만 통일 (이전에는 Spring Boot 기본 /error 형식으로 응답)
    // - 그 외 예상하지 못한 예외는 500: 내부 메시지는 응답에 넣지 않고 traceId로 서버 로그를 찾도록 안내
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiErrorResponse> handleUnexpected(Exception e, HttpServletRequest request) {
        if (e instanceof ErrorResponse errorResponse) {
            HttpStatus status = HttpStatus.resolve(errorResponse.getStatusCode().value());
            if (status != null && status.is4xxClientError()) {
                String message = frameworkMessage(status);
                log.warn("요청 실패 status={} message={}", status.value(), message);
                // 405의 Allow 헤더처럼 표준 예외가 가진 헤더는 그대로 전달
                return jsonError(status).headers(errorResponse.getHeaders())
                        .body(ApiErrorResponse.of(status, message, request.getRequestURI()));
            }
        }
        log.error("처리되지 않은 예외 {} {}", request.getMethod(), request.getRequestURI(), e);
        return jsonError(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiErrorResponse.of(HttpStatus.INTERNAL_SERVER_ERROR,
                        "서버 내부 오류가 발생했습니다. 문제가 계속되면 traceId와 함께 문의해주세요.",
                        request.getRequestURI()));
    }

    private ResponseEntity<ApiErrorResponse> clientError(HttpStatus status, String message,
                                                         HttpServletRequest request) {
        return clientError(status, null, message, request);
    }

    private ResponseEntity<ApiErrorResponse> clientError(HttpStatus status, ErrorCode code, String message,
                                                         HttpServletRequest request) {
        log.warn("요청 실패 status={} code={} message={}", status.value(), code, message);
        return jsonError(status)
                .body(ApiErrorResponse.of(status, code, message, request.getRequestURI()));
    }

    private String frameworkMessage(HttpStatus status) {
        return switch (status) {
            case NOT_FOUND -> "요청한 경로를 찾을 수 없습니다.";
            case METHOD_NOT_ALLOWED -> "지원하지 않는 HTTP 메서드입니다.";
            case UNSUPPORTED_MEDIA_TYPE -> "지원하지 않는 Content-Type입니다.";
            default -> "요청을 처리할 수 없습니다.";
        };
    }

    // 오류 본문은 항상 JSON으로 — Content-Type을 미리 정해 두면 Accept와 협상하지 않음
    // SSE 구독(#76)처럼 Accept가 text/event-stream뿐인 요청에서 403·404를 내면, 협상 실패로 원래 상태 코드 대신 500이 나가던 문제 방지
    private static ResponseEntity.BodyBuilder jsonError(HttpStatusCode status) {
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON);
    }
}
