package com.sparta.springdeliverymini.pg;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.sparta.springdeliverymini.config.TossPaymentsProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

// 토스페이먼츠 코어 API 호출 (테스트 모드) — 승인·조회·취소. toss.payments.mode=real(기본)일 때의 구현
// (fake면 토스 서버에 접속하지 않는 FakeTossPaymentsClient)
//
// - 인증: Authorization: Basic base64("{시크릿 키}:") — 시크릿 키는 이 클래스 밖으로 나가지 않고 로그·예외 메시지에 넣지 않음
// - 결과를 두 가지로 나눠 던짐 (호출하는 쪽이 금전 처리를 다르게 해야 하므로)
//   TossApiException          : 토스가 처리하고 명확히 거절(4xx) → 결과 확정
//   TossUnknownResultException: 처리됐는지 모름(시간 초과·연결 끊김·5xx·처리 중 409) → 조회로 확인하거나 같은 멱등 키로 재요청
// - Idempotency-Key 헤더(POST만): 같은 키·같은 요청이면 토스가 처음 응답을 그대로 돌려줌(15일 유효)
//   → 시간 초과 뒤 같은 키로 다시 보내도 승인·취소가 두 번 되지 않음
// - 리다이렉트는 따라가지 않음 (인증 헤더·본문이 예상하지 못한 곳으로 가지 않도록)
@Slf4j
@Component
@ConditionalOnProperty(name = "toss.payments.mode", havingValue = "real", matchIfMissing = true)
public class TossPaymentsHttpClient implements TossPaymentsClient {

    private static final String IDEMPOTENT_REQUEST_PROCESSING = "IDEMPOTENT_REQUEST_PROCESSING";

    private final TossPaymentsProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public TossPaymentsHttpClient(TossPaymentsProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(properties.connectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        log.info("토스페이먼츠 결제: {}", properties.enabled() ? "켜짐(테스트 모드)" : "꺼짐(키 없음 — 모의 결제만)");
    }

    @Override
    public boolean enabled() {
        return properties.enabled();
    }

    @Override
    public String clientKey() {
        return properties.clientKey();
    }

    @Override
    public TossPaymentsProperties.Mode mode() {
        return TossPaymentsProperties.Mode.REAL;
    }

    // 결제 승인 — 결제창 인증 후 10분 안에 호출해야 함
    @Override
    public TossPayment confirm(String paymentKey, String orderId, long amount, String idempotencyKey) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("paymentKey", paymentKey);
        body.put("orderId", orderId);
        body.put("amount", amount);
        return send(post("/v1/payments/confirm", body, idempotencyKey), properties.confirmTimeout(), "confirm");
    }

    // 결제 조회 — 승인·취소 결과를 모를 때 실제 상태 확인 (GET이라 멱등 키 없음)
    @Override
    public TossPayment getPayment(String paymentKey) {
        HttpRequest.Builder request = HttpRequest.newBuilder(uri("/v1/payments/" + encode(paymentKey))).GET();
        return send(request, properties.queryTimeout(), "query");
    }

    // 취소 — cancelAmount가 null이면 전액, 있으면 그 금액만(부분 취소, V21). 이미 전액 취소된 결제는 ALREADY_CANCELED_PAYMENT
    @Override
    public TossPayment cancel(String paymentKey, String cancelReason, Long cancelAmount, String idempotencyKey) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("cancelReason", cancelReason);
        if (cancelAmount != null) {
            body.put("cancelAmount", cancelAmount);
        }
        return send(post("/v1/payments/" + encode(paymentKey) + "/cancel", body, idempotencyKey),
                properties.cancelTimeout(), "cancel");
    }

    private HttpRequest.Builder post(String path, Map<String, Object> body, String idempotencyKey) {
        HttpRequest.Builder request = HttpRequest.newBuilder(uri(path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body), StandardCharsets.UTF_8));
        if (idempotencyKey != null) {
            request.header("Idempotency-Key", idempotencyKey);
        }
        return request;
    }

    private TossPayment send(HttpRequest.Builder builder, Duration timeout, String operation) {
        if (!properties.enabled()) {
            // 키 없이 호출 = 설정 문제. 요청을 보내지 않았으므로 결과는 확정(아무 일도 없음)
            throw new TossApiException(0, "PG_NOT_CONFIGURED", "토스페이먼츠 키가 설정되지 않았습니다.");
        }
        HttpRequest request = builder
                // HttpClient의 요청 timeout = 요청을 보낸 뒤 응답 헤더를 받기까지의 상한
                .timeout(timeout)
                .header("Authorization", authorization())
                .header("Accept", "application/json")
                .build();

        HttpResponse<String> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (HttpConnectTimeoutException e) {
            throw new TossUnknownResultException("토스페이먼츠 연결 시간 초과 operation=" + operation);
        } catch (HttpTimeoutException e) {
            throw new TossUnknownResultException("토스페이먼츠 응답 시간 초과 operation=" + operation);
        } catch (IOException e) {
            // 연결 거부·끊김·TLS 오류 — 요청이 도착했는지 알 수 없으므로 결과 불명으로 다룸
            throw new TossUnknownResultException(
                    "토스페이먼츠 요청 실패 operation=" + operation + " (" + e.getClass().getSimpleName() + ")");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new TossUnknownResultException("토스페이먼츠 요청 중 인터럽트 operation=" + operation);
        }

        int status = response.statusCode();
        if (status >= 200 && status < 300) {
            try {
                return objectMapper.readValue(response.body(), TossPayment.class);
            } catch (JacksonException e) {
                throw new TossUnknownResultException("토스페이먼츠 응답 해석 실패 operation=" + operation);
            }
        }

        TossError error = parseError(response.body());
        // 5xx: 토스 내부 오류 — 처리 여부를 단정하지 않음 / 409 처리 중: 같은 멱등 키의 이전 요청이 아직 진행 중
        if (status >= 500 || IDEMPOTENT_REQUEST_PROCESSING.equals(error.code())) {
            throw new TossUnknownResultException(
                    "토스페이먼츠 결과 불명 operation=" + operation + ", status=" + status + ", code=" + error.code());
        }
        throw new TossApiException(status, error.code(), error.message());
    }

    private TossError parseError(String body) {
        try {
            TossError error = objectMapper.readValue(body, TossError.class);
            return error.code() == null ? new TossError("UNKNOWN", null) : error;
        } catch (JacksonException | IllegalArgumentException e) {
            return new TossError("UNKNOWN", null);
        }
    }

    private URI uri(String path) {
        String base = properties.baseUrl().toString();
        return URI.create(base.endsWith("/") ? base.substring(0, base.length() - 1) + path : base + path);
    }

    private String authorization() {
        String credentials = properties.secretKey() + ":";
        return "Basic " + Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
    }

    // 결제 키는 경로에 들어가므로 인코딩 (경로를 바꾸는 문자 '/'·'?' 등이 들어와도 다른 API로 가지 않음)
    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record TossError(String code, String message) {
    }
}
