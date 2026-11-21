package com.sparta.springdeliverymini.event;

import com.sparta.springdeliverymini.config.NotificationProperties;
import com.sparta.springdeliverymini.entity.Notification;
import com.sparta.springdeliverymini.entity.OutboxEventType;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

// Slack Incoming Webhook 발송기 (#69, 빈 등록은 config.BackgroundTaskConfig — notification.sender=slack)
//
// - 사용자별 채널이 아니라 운영 채널 1곳으로 보내는 데모 기능: 웹훅 URL 하나 = 채널 하나
// - 문구에는 notification id·이벤트 종류만 넣고 주문 번호·금액·user id는 넣지 않음
//   → 운영 채널 참가자 모두가 보는 외부 서비스이므로 개인·거래 정보를 내보내지 않음. 자세한 내용은 id로 DB에서 확인
// - 실패(연결·응답 시간 초과, 비 2xx, 429 포함)는 SlackSendException으로 던짐
//   → NotificationDispatcher의 기존 재시도(retry-delay)·FAILED 전환을 그대로 씀. 429의 Retry-After는 따르지 않음
//     (재시도 간격 1분이 Slack 한도 대기보다 길어 별도 처리가 필요 없음)
// - 예외 메시지는 last_send_error 컬럼과 로그에 남으므로 웹훅 URL을 넣지 않음 (원인 예외도 붙이지 않음 — 메시지에 주소가 들어 있을 수 있어서)
// - 최소 한 번 발송이라 같은 알림이 두 번 갈 수 있음 → 문구의 notification id로 구분
@Slf4j
public class SlackNotificationSender implements NotificationSender {

    // 오류 본문은 원인 파악용으로 앞부분만 (Slack은 "invalid_token"·"no_service" 같은 짧은 문자열을 돌려줌)
    private static final int MAX_ERROR_BODY_LENGTH = 200;

    private final HttpClient httpClient;
    private final URI webhookUri;
    private final Duration readTimeout;
    private final ObjectMapper objectMapper;

    SlackNotificationSender(HttpClient httpClient, URI webhookUri, Duration readTimeout, ObjectMapper objectMapper) {
        this.httpClient = httpClient;
        this.webhookUri = webhookUri;
        this.readTimeout = readTimeout;
        this.objectMapper = objectMapper;
    }

    public static SlackNotificationSender create(NotificationProperties.Slack slack, ObjectMapper objectMapper) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(slack.connectTimeout())
                // 웹훅은 리다이렉트하지 않음 — 따라가면 요청 본문이 예상하지 못한 곳으로 갈 수 있음
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        log.info("알림 발송기: Slack Incoming Webhook (connectTimeout={}, readTimeout={})",
                slack.connectTimeout(), slack.readTimeout());
        return new SlackNotificationSender(httpClient, URI.create(slack.webhookUrl().trim()), slack.readTimeout(),
                objectMapper);
    }

    @Override
    public void send(Notification notification) {
        HttpRequest request = HttpRequest.newBuilder(webhookUri)
                // HttpClient의 요청 timeout = 요청 전송 후 응답 헤더를 받기까지의 상한 (읽기 시간 초과)
                .timeout(readTimeout)
                .header("Content-Type", "application/json; charset=utf-8")
                .POST(HttpRequest.BodyPublishers.ofString(body(notification), StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (HttpConnectTimeoutException e) {
            throw new SlackSendException("Slack 연결 시간 초과");
        } catch (HttpTimeoutException e) {
            throw new SlackSendException("Slack 응답 시간 초과");
        } catch (IOException e) {
            // 연결 거부·TLS 오류 등 — 예외 종류만 남김
            throw new SlackSendException("Slack 요청 실패 (" + e.getClass().getSimpleName() + ")");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SlackSendException("Slack 발송 중 인터럽트");
        }

        int status = response.statusCode();
        if (status == 429) {
            throw new SlackSendException("Slack 요청 한도 초과 status=429, retryAfter="
                    + response.headers().firstValue("Retry-After").orElse("-"));
        }
        if (status < 200 || status >= 300) {
            throw new SlackSendException("Slack 비정상 응답 status=" + status + ", body=" + abbreviate(response.body()));
        }
        log.debug("Slack 알림 발송 notificationId={}", notification.getId());
    }

    // {"text": "..."} — Slack이 받는 가장 단순한 형식. 문자열 이스케이프는 Jackson에 맡김
    String body(Notification notification) {
        return objectMapper.writeValueAsString(Map.of("text", text(notification)));
    }

    static String text(Notification notification) {
        return "[배달 알림] " + label(notification.getEventType())
                + " 알림이 생성되었습니다. (notificationId=" + notification.getId() + ")";
    }

    // 새 이벤트 종류가 생기면 여기서 컴파일 오류 → 문구를 함께 정함
    private static String label(OutboxEventType type) {
        return switch (type) {
            case PAYMENT_COMPLETED -> "결제 완료";
            case PAYMENT_REFUNDED -> "환불 완료";
            case DELIVERY_COMPLETED -> "배달 완료";
        };
    }

    private static String abbreviate(String body) {
        if (body == null || body.isBlank()) {
            return "(없음)";
        }
        String oneLine = body.strip().replaceAll("\\s+", " ");
        return oneLine.length() <= MAX_ERROR_BODY_LENGTH ? oneLine : oneLine.substring(0, MAX_ERROR_BODY_LENGTH) + "...";
    }

    // 발송 실패 — 메시지는 last_send_error에 저장되므로 비밀 값(웹훅 URL)을 넣지 않음
    public static class SlackSendException extends RuntimeException {
        public SlackSendException(String message) {
            super(message);
        }
    }
}
