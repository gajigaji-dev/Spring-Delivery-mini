package com.sparta.springdeliverymini.exception;

import lombok.Getter;
import org.springframework.http.HttpStatus;

@Getter
public class ApiException extends RuntimeException {

    private final HttpStatus status;

    // 응답 본문의 code (없으면 null) — 클라이언트가 같은 상태 코드 안에서 대응을 나눠야 할 때만 지정 (ErrorCode)
    private final ErrorCode code;

    public ApiException(HttpStatus status, String message) {
        this(status, null, message);
    }

    public ApiException(HttpStatus status, ErrorCode code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }
}
