package com.sparta.springdeliverymini.pg;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;

// 토스 Payment 객체 중 이 서비스가 쓰는 값만 (나머지 필드는 무시 — 카드 번호 일부 등 개인정보를 들고 다니지 않음)
// status: READY, IN_PROGRESS, WAITING_FOR_DEPOSIT, DONE, CANCELED, PARTIAL_CANCELED, ABORTED, EXPIRED
// approvedAt: ISO 8601 (yyyy-MM-dd'T'HH:mm:ss±hh:mm)
@JsonIgnoreProperties(ignoreUnknown = true)
public record TossPayment(String paymentKey, String orderId, String status, Long totalAmount, Long balanceAmount,
                          String method, String approvedAt) {

    public static final String DONE = "DONE";
    public static final String CANCELED = "CANCELED";

    public boolean isDone() {
        return DONE.equals(status);
    }

    public boolean isCanceled() {
        return CANCELED.equals(status);
    }

    // 승인이 끝나지 않았고 앞으로도 승인될 수 없는 상태 — 결제창을 다시 열어야 함
    public boolean isClosedWithoutApproval() {
        return "ABORTED".equals(status) || "EXPIRED".equals(status);
    }

    // 승인 시각을 서비스 시간대(Asia/Seoul) LocalDateTime으로 — DB 정밀도(마이크로초)에 맞춰 자름
    // 형식이 다르거나 없으면 null → 호출하는 쪽이 서버 시각으로 대신함
    public LocalDateTime approvedAtIn(ZoneId zone) {
        if (approvedAt == null || approvedAt.isBlank()) {
            return null;
        }
        try {
            return OffsetDateTime.parse(approvedAt).atZoneSameInstant(zone).toLocalDateTime()
                    .truncatedTo(ChronoUnit.MICROS);
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
