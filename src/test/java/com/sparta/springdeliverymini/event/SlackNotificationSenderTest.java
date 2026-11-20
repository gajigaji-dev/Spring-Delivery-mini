package com.sparta.springdeliverymini.event;

import com.sparta.springdeliverymini.config.NotificationProperties;
import com.sparta.springdeliverymini.entity.Notification;
import com.sparta.springdeliverymini.entity.NotificationSendStatus;
import com.sparta.springdeliverymini.entity.OutboxEventType;
import com.sparta.springdeliverymini.event.SlackNotificationSender.SlackSendException;
import com.sparta.springdeliverymini.repository.NotificationRepository;
import com.sun.net.httpserver.HttpServer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

// Slack Incoming Webhook 발송기 (#69) — 로컬 HTTP 서버(JDK 내장)를 웹훅 대신 세워 응답 코드·지연을 흉내 냄
class SlackNotificationSenderTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 20, 12, 0);
    private static final long ORDER_ID = 98765L;
    private static final long RECIPIENT_USER_ID = 4321L;
    // 실제 웹훅처럼 경로에 비밀 값이 들어 있다고 가정 — 오류 메시지에 이 값이 나오면 안 됨
    private static final String SECRET_PATH = "/services/T000SECRET/B000SECRET/xyzSecretToken";

    private final ObjectMapper objectMapper = JsonMapper.builder().build();
    private final AtomicInteger status = new AtomicInteger(200);
    private final AtomicReference<String> responseBody = new AtomicReference<>("ok");
    private final AtomicReference<String> retryAfter = new AtomicReference<>();
    private final List<String> receivedBodies = new CopyOnWriteArrayList<>();
    private volatile CountDownLatch slowResponse; // null이 아니면 이 신호가 올 때까지 응답을 미룸
    private HttpServer server;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", exchange -> {
            receivedBodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            CountDownLatch latch = slowResponse;
            if (latch != null) {
                try {
                    latch.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            if (retryAfter.get() != null) {
                exchange.getResponseHeaders().add("Retry-After", retryAfter.get());
            }
            byte[] bytes = responseBody.get().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status.get(), bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
        server.start();
    }

    @AfterEach
    void stopServer() {
        if (slowResponse != null) {
            slowResponse.countDown();
        }
        server.stop(0);
    }

    private String webhookUrl() {
        return "http://" + server.getAddress().getHostString() + ":" + server.getAddress().getPort() + SECRET_PATH;
    }

    private SlackNotificationSender sender(Duration readTimeout) {
        return SlackNotificationSender.create(
                new NotificationProperties.Slack(webhookUrl(), Duration.ofSeconds(2), readTimeout), objectMapper);
    }

    private static Notification notification(long id, OutboxEventType type) {
        Notification notification = new Notification(UUID.randomUUID(), type, RECIPIENT_USER_ID, ORDER_ID,
                "주문 " + ORDER_ID + " 결제가 완료되었습니다. 금액 25000원", NOW);
        ReflectionTestUtils.setField(notification, "id", id);
        return notification;
    }

    @Test
    void 성공하면_notification_id와_이벤트_종류만_담아_보내고_주문번호_금액_사용자id는_넣지_않는다() {
        sender(Duration.ofSeconds(2)).send(notification(777L, OutboxEventType.PAYMENT_COMPLETED));

        assertThat(receivedBodies).hasSize(1);
        JsonNode body = objectMapper.readTree(receivedBodies.getFirst());
        String text = body.get("text").asString();
        assertThat(text).contains("777").contains("결제 완료");
        assertThat(text).doesNotContain(String.valueOf(ORDER_ID))
                .doesNotContain(String.valueOf(RECIPIENT_USER_ID))
                .doesNotContain("25000");
        // text 외 다른 필드(채널 지정 등)는 보내지 않음 — 채널은 웹훅 URL이 정함
        assertThat(body.size()).isEqualTo(1);
    }

    @Test
    void 모든_이벤트_종류에_문구가_있다() {
        for (OutboxEventType type : OutboxEventType.values()) {
            assertThat(SlackNotificationSender.text(notification(1L, type))).doesNotContain(type.name());
        }
    }

    @Test
    void 응답이_4xx면_상태_코드와_본문을_담아_실패하고_URL은_노출하지_않는다() {
        status.set(404);
        responseBody.set("no_service");

        assertThatThrownBy(() -> sender(Duration.ofSeconds(2)).send(notification(1L, OutboxEventType.PAYMENT_COMPLETED)))
                .isInstanceOf(SlackSendException.class)
                .hasMessageContaining("status=404")
                .hasMessageContaining("no_service")
                .hasMessageNotContaining("SECRET")
                .hasNoCause();
    }

    @Test
    void 응답이_5xx면_실패한다() {
        status.set(503);
        responseBody.set("x".repeat(1000)); // 긴 본문은 잘라서 담음

        assertThatThrownBy(() -> sender(Duration.ofSeconds(2)).send(notification(1L, OutboxEventType.DELIVERY_COMPLETED)))
                .isInstanceOf(SlackSendException.class)
                .hasMessageContaining("status=503")
                .satisfies(e -> assertThat(e.getMessage()).hasSizeLessThan(300));
    }

    @Test
    void 요청_한도를_넘으면_429로_실패하고_Retry_After를_남긴다() {
        status.set(429);
        responseBody.set("rate_limited");
        retryAfter.set("30");

        assertThatThrownBy(() -> sender(Duration.ofSeconds(2)).send(notification(1L, OutboxEventType.PAYMENT_REFUNDED)))
                .isInstanceOf(SlackSendException.class)
                .hasMessageContaining("status=429")
                .hasMessageContaining("retryAfter=30");
    }

    @Test
    void 응답이_읽기_시간_안에_오지_않으면_시간_초과로_실패한다() {
        slowResponse = new CountDownLatch(1);
        SlackNotificationSender sender = sender(Duration.ofMillis(300));

        long started = System.nanoTime();
        assertThatThrownBy(() -> sender.send(notification(1L, OutboxEventType.PAYMENT_COMPLETED)))
                .isInstanceOf(SlackSendException.class)
                .hasMessage("Slack 응답 시간 초과");
        // 서버가 5초 동안 응답하지 않아도 읽기 시간 초과(0.3초) 근처에서 끝남
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(3));
    }

    // 연결 시간 초과는 로컬에서 재현이 불안정(라우팅되지 않는 주소는 환경마다 즉시 거부되기도 함) → HttpClient를 가짜로 바꿔 확인
    @Test
    void 연결_시간_초과면_연결_시간_초과로_실패한다() throws Exception {
        HttpClient httpClient = mock(HttpClient.class);
        given(httpClient.send(any(), any())).willThrow(new HttpConnectTimeoutException("HTTP connect timed out"));
        SlackNotificationSender sender = new SlackNotificationSender(httpClient, URI.create(webhookUrl()),
                Duration.ofSeconds(1), objectMapper);

        assertThatThrownBy(() -> sender.send(notification(1L, OutboxEventType.PAYMENT_COMPLETED)))
                .isInstanceOf(SlackSendException.class)
                .hasMessage("Slack 연결 시간 초과");
    }

    @Test
    void 연결이_거부되면_예외_종류만_담아_실패한다() {
        String url = webhookUrl();
        server.stop(0); // 포트를 닫아 연결 거부

        assertThatThrownBy(() -> SlackNotificationSender.create(
                new NotificationProperties.Slack(url, Duration.ofSeconds(1), Duration.ofSeconds(1)), objectMapper)
                .send(notification(1L, OutboxEventType.PAYMENT_COMPLETED)))
                .isInstanceOf(SlackSendException.class)
                .hasMessageStartingWith("Slack 요청 실패")
                .hasMessageNotContaining("SECRET");
    }

    // 발송기 예외 → NotificationDispatcher의 기존 재시도·FAILED 전환이 그대로 동작하는지 (DB 대신 저장소만 가짜)
    @Test
    void 발송이_계속_실패하면_재시도하다가_최대_횟수에서_FAILED가_되고_오류에_URL이_남지_않는다() {
        status.set(500);
        responseBody.set("internal_error");
        Notification notification = notification(10L, OutboxEventType.PAYMENT_COMPLETED);
        NotificationRepository repository = mock(NotificationRepository.class);
        given(repository.findSendableForUpdate(any(), anyInt())).willReturn(List.of(notification));
        NotificationProperties properties = new NotificationProperties(
                new NotificationProperties.Dispatcher(true, Duration.ofSeconds(5), 100, 3, Duration.ofMinutes(1)),
                NotificationProperties.Sender.SLACK,
                new NotificationProperties.Slack(webhookUrl(), Duration.ofSeconds(2), Duration.ofSeconds(2)));
        NotificationDispatcher dispatcher = new NotificationDispatcher(repository,
                SlackNotificationSender.create(properties.slack(), objectMapper), properties,
                Clock.fixed(NOW.atZone(ZoneId.of("Asia/Seoul")).toInstant(), ZoneId.of("Asia/Seoul")),
                new SimpleMeterRegistry());

        assertThat(dispatcher.dispatchPending()).isEqualTo(new NotificationDispatcher.DispatchResult(0, 1, 0));
        assertThat(dispatcher.dispatchPending()).isEqualTo(new NotificationDispatcher.DispatchResult(0, 1, 0));
        assertThat(notification.getSendStatus()).isEqualTo(NotificationSendStatus.PENDING);
        assertThat(notification.getNextSendAt()).isEqualTo(NOW.plusMinutes(1));

        assertThat(dispatcher.dispatchPending()).isEqualTo(new NotificationDispatcher.DispatchResult(0, 0, 1));
        assertThat(notification.getSendStatus()).isEqualTo(NotificationSendStatus.FAILED);
        assertThat(notification.getSendAttemptCount()).isEqualTo(3);
        assertThat(notification.getLastSendError()).contains("status=500").doesNotContain("SECRET");
        assertThat(receivedBodies).hasSize(3);
    }

    @Test
    void 실패한_뒤_Slack이_회복되면_다음_시도에서_SENT가_된다() {
        status.set(503);
        Notification notification = notification(11L, OutboxEventType.PAYMENT_COMPLETED);
        NotificationRepository repository = mock(NotificationRepository.class);
        given(repository.findSendableForUpdate(any(), anyInt())).willReturn(List.of(notification));
        NotificationProperties properties = new NotificationProperties(
                new NotificationProperties.Dispatcher(true, Duration.ofSeconds(5), 100, 5, Duration.ofMinutes(1)),
                NotificationProperties.Sender.SLACK,
                new NotificationProperties.Slack(webhookUrl(), Duration.ofSeconds(2), Duration.ofSeconds(2)));
        NotificationDispatcher dispatcher = new NotificationDispatcher(repository,
                SlackNotificationSender.create(properties.slack(), objectMapper), properties,
                Clock.fixed(NOW.atZone(ZoneId.of("Asia/Seoul")).toInstant(), ZoneId.of("Asia/Seoul")),
                new SimpleMeterRegistry());

        dispatcher.dispatchPending();
        status.set(200);
        assertThatCode(dispatcher::dispatchPending).doesNotThrowAnyException();

        assertThat(notification.getSendStatus()).isEqualTo(NotificationSendStatus.SENT);
        assertThat(notification.getSendAttemptCount()).isEqualTo(1);
    }
}
