package com.sparta.springdeliverymini.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.StringUtils;

import java.net.URI;
import java.time.Duration;

// application.yml의 notification.* — 알림 발송 설정 (event.NotificationDispatcher, docs/architecture.md Kafka 장)
// 값이 없거나 범위를 벗어나면 바인딩 단계에서 예외 → 애플리케이션 기동 실패
// sender: 발송 채널 — log(기본, 로그 출력) / slack(운영 채널 1곳으로 Incoming Webhook 발송, #69)
//   알 수 없는 값은 enum 바인딩에서 실패 → 오타로 조용히 로그 발송기가 쓰이지 않도록
@ConfigurationProperties(prefix = "notification")
public record NotificationProperties(Dispatcher dispatcher, Sender sender, Slack slack) {

    public enum Sender { LOG, SLACK }

    public NotificationProperties {
        if (dispatcher == null) {
            throw new IllegalArgumentException("notification.dispatcher 설정이 필요합니다.");
        }
        if (sender == null) {
            sender = Sender.LOG;
        }
        // slack을 골랐는데 URL이 없으면 모든 발송이 실패해 FAILED로 쌓이기만 함 → 프로필과 관계없이 기동 시점에 막음
        // (prod는 ProdProfileGuard가 빈 생성 전에 SLACK_WEBHOOK_URL 이름으로 한 번 더 알려 줌)
        if (sender == Sender.SLACK) {
            if (slack == null) {
                throw new IllegalArgumentException("notification.sender=slack이면 notification.slack 설정이 필요합니다.");
            }
            slack.validate();
        }
    }

    // enabled: 이 인스턴스에서 주기 발송을 할지 (false여도 알림 기록 저장은 계속됨 → 켜진 인스턴스가 발송)
    // fixed-delay: 한 번 발송을 마친 뒤 다음 조회까지 쉬는 시간 / batch-size: 한 번에 가져오는 알림 수
    // max-attempts: 발송 실패가 이 횟수에 닿으면 FAILED (자동 발송 중단)
    // retry-delay: 실패한 알림을 다시 시도하기까지의 간격 (고정 간격 — 알림은 늦게 도착할수록 가치가 떨어져 오래 재시도하지 않음)
    public record Dispatcher(boolean enabled, Duration fixedDelay, int batchSize, int maxAttempts,
                             Duration retryDelay) {

        public Dispatcher {
            requirePositive("dispatcher.fixed-delay", fixedDelay);
            requirePositive("dispatcher.retry-delay", retryDelay);
            if (batchSize < 1 || maxAttempts < 1) {
                throw new IllegalArgumentException("notification.dispatcher.batch-size, max-attempts는 1 이상이어야 합니다.");
            }
        }
    }

    // Slack Incoming Webhook (#69) — webhook-url은 기본값 없이 SLACK_WEBHOOK_URL 환경변수로만 받음
    // URL 자체가 발송 권한(비밀 값)이라 오류 메시지·toString에 값을 넣지 않음
    // connect-timeout: TCP·TLS 연결 상한 / read-timeout: 요청을 보낸 뒤 응답을 받기까지의 상한
    //   발송은 알림 행을 잠근 트랜잭션 안에서 하므로, 상한이 없으면 Slack 장애 때 잠금·커넥션을 오래 잡음
    public record Slack(String webhookUrl, Duration connectTimeout, Duration readTimeout) {

        void validate() {
            if (!StringUtils.hasText(webhookUrl)) {
                throw new IllegalArgumentException(
                        "notification.sender=slack이면 notification.slack.webhook-url(SLACK_WEBHOOK_URL)이 필요합니다.");
            }
            if (!isHttpUrl(webhookUrl)) {
                throw new IllegalArgumentException(
                        "notification.slack.webhook-url은 http(s) 절대 주소여야 합니다. (값은 보안상 출력하지 않음)");
            }
            requirePositive("slack.connect-timeout", connectTimeout);
            requirePositive("slack.read-timeout", readTimeout);
        }

        private static boolean isHttpUrl(String value) {
            try {
                URI uri = URI.create(value.trim());
                return uri.getHost() != null
                        && ("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()));
            } catch (IllegalArgumentException e) {
                return false;
            }
        }

        // 설정 객체가 로그·디버거·actuator 등에 문자열로 찍혀도 URL이 남지 않도록 가림
        @Override
        public String toString() {
            return "Slack[webhookUrl=" + (StringUtils.hasText(webhookUrl) ? "******" : "(없음)")
                    + ", connectTimeout=" + connectTimeout + ", readTimeout=" + readTimeout + "]";
        }
    }

    private static void requirePositive(String name, Duration value) {
        if (value == null || value.isNegative() || value.isZero()) {
            throw new IllegalArgumentException("notification." + name + "는 0보다 커야 합니다: " + value);
        }
    }
}
