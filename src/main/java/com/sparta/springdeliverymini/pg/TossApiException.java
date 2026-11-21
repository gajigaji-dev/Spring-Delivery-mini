package com.sparta.springdeliverymini.pg;

// 토스가 요청을 처리하고 명확히 거절한 경우 (4xx + {code, message}) — 결과가 확정됨
// 예: REJECT_CARD_COMPANY(카드사 거절), NOT_FOUND_PAYMENT_SESSION(결제 시간 만료), ALREADY_PROCESSED_PAYMENT(이미 승인됨)
// message는 토스가 사용자에게 보여 주라고 주는 문구 — 시크릿 키 등 비밀 값은 들어 있지 않음
public class TossApiException extends RuntimeException {

    public static final String ALREADY_PROCESSED_PAYMENT = "ALREADY_PROCESSED_PAYMENT";
    public static final String ALREADY_CANCELED_PAYMENT = "ALREADY_CANCELED_PAYMENT";

    private final int httpStatus;
    private final String code;
    private final String tossMessage;

    public TossApiException(int httpStatus, String code, String tossMessage) {
        super("토스페이먼츠 요청 거절 status=" + httpStatus + ", code=" + code);
        this.httpStatus = httpStatus;
        this.code = code;
        this.tossMessage = tossMessage;
    }

    public int httpStatus() {
        return httpStatus;
    }

    public String code() {
        return code;
    }

    public String tossMessage() {
        return tossMessage;
    }
}
