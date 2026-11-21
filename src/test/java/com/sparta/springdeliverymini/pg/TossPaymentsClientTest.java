package com.sparta.springdeliverymini.pg;

import com.sparta.springdeliverymini.config.TossPaymentsProperties;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// 토스 API 호출 형식·결과 구분을 로컬 HTTP 서버로 확인 (실제 토스 API를 부르지 않음)
// - 확정 거절(4xx)은 TossApiException, 처리 여부를 모르는 경우(시간 초과·5xx·처리 중 409)는 TossUnknownResultException
class TossPaymentsClientTest {

    private static final String SECRET_KEY = "test_sk_local_only";

    private HttpServer server;
    private final AtomicInteger responseStatus = new AtomicInteger(200);
    private final AtomicReference<String> responseBody = new AtomicReference<>("{}");
    private final AtomicLong responseDelayMs = new AtomicLong(0);
    private final List<Recorded> requests = new ArrayList<>();

    record Recorded(String method, String path, String authorization, String idempotencyKey, String body) {
    }

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            synchronized (requests) {
                requests.add(new Recorded(exchange.getRequestMethod(), exchange.getRequestURI().getRawPath(),
                        exchange.getRequestHeaders().getFirst("Authorization"),
                        exchange.getRequestHeaders().getFirst("Idempotency-Key"), body));
            }
            try {
                Thread.sleep(responseDelayMs.get());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            byte[] bytes = responseBody.get().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(responseStatus.get(), bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    private TossPaymentsHttpClient client(Duration readTimeout) {
        URI baseUrl = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
        TossPaymentsProperties properties = new TossPaymentsProperties("test_ck_local_only", SECRET_KEY, baseUrl,
                Duration.ofSeconds(1), readTimeout, readTimeout, readTimeout,
                new TossPaymentsProperties.Cancel(false, Duration.ofSeconds(10), 20, 10, Duration.ofSeconds(10),
                        Duration.ofMinutes(10), Duration.ofMinutes(1)), null);
        return new TossPaymentsHttpClient(properties, JsonMapper.builder().build());
    }

    @Test
    void 승인은_시크릿_키_Basic_인증과_멱등_키로_요청하고_응답을_해석한다() {
        responseBody.set("""
                {"paymentKey":"pk_1","orderId":"order-1-0123456789abcdef","status":"DONE","totalAmount":36000,
                 "balanceAmount":36000,"method":"카드","approvedAt":"2026-11-21T12:00:00+09:00",
                 "card":{"number":"1234********5678"}}
                """);

        TossPayment payment = client(Duration.ofSeconds(2)).confirm("pk_1", "order-1-0123456789abcdef", 36000, "confirm-pk_1");

        assertThat(payment.isDone()).isTrue();
        assertThat(payment.totalAmount()).isEqualTo(36000);
        assertThat(payment.approvedAtIn(java.time.ZoneId.of("Asia/Seoul")))
                .isEqualTo(java.time.LocalDateTime.of(2026, 11, 21, 12, 0));
        Recorded request = requests.getFirst();
        assertThat(request.method()).isEqualTo("POST");
        assertThat(request.path()).isEqualTo("/v1/payments/confirm");
        assertThat(request.authorization()).isEqualTo("Basic "
                + Base64.getEncoder().encodeToString((SECRET_KEY + ":").getBytes(StandardCharsets.UTF_8)));
        assertThat(request.idempotencyKey()).isEqualTo("confirm-pk_1");
        assertThat(request.body()).contains("\"paymentKey\":\"pk_1\"", "\"amount\":36000");
    }

    @Test
    void 전액_취소는_결제_키_경로로_요청하고_금액은_보내지_않는다() {
        responseBody.set("{\"paymentKey\":\"pk_1\",\"status\":\"CANCELED\",\"totalAmount\":36000,\"balanceAmount\":0}");

        TossPayment payment = client(Duration.ofSeconds(2)).cancel("pk_1", "고객 주문 취소", null, "cancel-1-1");

        assertThat(payment.isCanceled()).isTrue();
        Recorded request = requests.getFirst();
        assertThat(request.path()).isEqualTo("/v1/payments/pk_1/cancel");
        assertThat(request.idempotencyKey()).isEqualTo("cancel-1-1");
        assertThat(request.body()).contains("고객 주문 취소").doesNotContain("cancelAmount");
    }

    @Test
    void 부분_취소는_취소_금액을_함께_보낸다() {
        responseBody.set("{\"paymentKey\":\"pk_1\",\"status\":\"PARTIAL_CANCELED\",\"totalAmount\":36000,\"balanceAmount\":33000}");

        TossPayment payment = client(Duration.ofSeconds(2)).cancel("pk_1", "누락 메뉴 환불", 3000L, "cancel-2-1");

        assertThat(payment.balanceAmount()).isEqualTo(33000);
        assertThat(requests.getFirst().body()).contains("\"cancelAmount\":3000");
    }

    @Test
    void 결제_키의_경로_문자는_인코딩되어_다른_API로_가지_않는다() {
        responseBody.set("{\"status\":\"DONE\"}");

        client(Duration.ofSeconds(2)).getPayment("../confirm?x=1");

        assertThat(requests.getFirst().method()).isEqualTo("GET");
        assertThat(requests.getFirst().path()).isEqualTo("/v1/payments/..%2Fconfirm%3Fx%3D1");
    }

    @Test
    void 사백번대_응답은_토스_오류_코드와_함께_확정_거절로_던진다() {
        responseStatus.set(403);
        responseBody.set("{\"code\":\"REJECT_CARD_COMPANY\",\"message\":\"결제 승인이 거절되었습니다.\"}");

        assertThatThrownBy(() -> client(Duration.ofSeconds(2)).confirm("pk_1", "order-1-x", 1000, "k"))
                .isInstanceOfSatisfying(TossApiException.class, e -> {
                    assertThat(e.httpStatus()).isEqualTo(403);
                    assertThat(e.code()).isEqualTo("REJECT_CARD_COMPANY");
                    assertThat(e.tossMessage()).isEqualTo("결제 승인이 거절되었습니다.");
                    assertThat(e.getMessage()).doesNotContain(SECRET_KEY);
                });
    }

    @Test
    void 오백번대_응답은_처리_여부를_모르는_결과로_던진다() {
        responseStatus.set(500);
        responseBody.set("{\"code\":\"FAILED_INTERNAL_SYSTEM_PROCESSING\",\"message\":\"내부 오류\"}");

        assertThatThrownBy(() -> client(Duration.ofSeconds(2)).confirm("pk_1", "order-1-x", 1000, "k"))
                .isInstanceOf(TossUnknownResultException.class);
    }

    @Test
    void 같은_멱등_키의_이전_요청이_처리_중이면_결과를_모르는_것으로_던진다() {
        responseStatus.set(409);
        responseBody.set("{\"code\":\"IDEMPOTENT_REQUEST_PROCESSING\",\"message\":\"이전 멱등 요청이 처리중입니다.\"}");

        assertThatThrownBy(() -> client(Duration.ofSeconds(2)).confirm("pk_1", "order-1-x", 1000, "k"))
                .isInstanceOf(TossUnknownResultException.class);
    }

    @Test
    void 응답_시간을_넘기면_결과를_모르는_것으로_던진다() {
        responseDelayMs.set(1500);
        responseBody.set("{\"status\":\"DONE\"}");

        assertThatThrownBy(() -> client(Duration.ofMillis(300)).confirm("pk_1", "order-1-x", 1000, "k"))
                .isInstanceOf(TossUnknownResultException.class)
                .hasMessageContaining("시간 초과");
    }

    @Test
    void 해석할_수_없는_성공_응답은_결과를_모르는_것으로_던진다() {
        responseBody.set("<html>gateway</html>");

        assertThatThrownBy(() -> client(Duration.ofSeconds(2)).getPayment("pk_1"))
                .isInstanceOf(TossUnknownResultException.class);
    }
}
