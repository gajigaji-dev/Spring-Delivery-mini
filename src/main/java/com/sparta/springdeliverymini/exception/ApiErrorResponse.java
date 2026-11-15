package com.sparta.springdeliverymini.exception;

import com.sparta.springdeliverymini.logging.TraceIdFilter;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;

/**
 * 모든 에러 응답의 공통 형식 (GlobalExceptionHandler, SecurityConfig의 401/403)
 * 기존 응답의 "message" 필드는 이름·의미를 그대로 유지하고 나머지는 추가만 함 → 기존 클라이언트가 깨지지 않음
 * <pre>
 * { "status": 409, "error": "Conflict", "message": "...", "path": "/api/...", "traceId": "..." }
 * </pre>
 * traceId는 응답 헤더 X-Trace-Id·서버 로그와 같은 값 → 문의받은 traceId로 서버 로그를 바로 찾을 수 있음
 */
public record ApiErrorResponse(int status, String error, String message, String path, String traceId) {

    public static ApiErrorResponse of(HttpStatus status, String message, String path) {
        return new ApiErrorResponse(status.value(), status.getReasonPhrase(), message, path,
                MDC.get(TraceIdFilter.MDC_KEY));
    }
}
