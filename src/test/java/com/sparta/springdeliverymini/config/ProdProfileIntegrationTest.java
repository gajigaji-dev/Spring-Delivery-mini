package com.sparta.springdeliverymini.config;

import com.sparta.springdeliverymini.TestcontainersConfiguration;
import com.sparta.springdeliverymini.entity.Role;
import com.sparta.springdeliverymini.jwt.JwtProvider;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;

// prod 프로필(application-prod.yml)을 실제로 적용해 띄운 뒤 HTTP로 노출 정책 확인 (#54)
// test 프로필 뒤에 prod를 적용 → 겹치는 설정은 prod가 우선. DB는 Testcontainers가 연결하고,
// 필수 환경변수는 ProdProfileGuard 통과용 값만 지정 (Redis·Kafka는 기동 중 접속하지 않음)
// 관리 포트도 임의 포트(0)로 열어 서비스 포트와 분리된 상태를 그대로 검증
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "DB_URL=jdbc:postgresql://overridden-by-testcontainers/delivery",
        "DB_USERNAME=guard-check-only",
        "DB_PASSWORD=guard-check-only",
        "JWT_SECRET=guard-check-only",
        "REDIS_HOST=localhost",
        "KAFKA_BOOTSTRAP_SERVERS=localhost:9094",
        "CORS_ALLOWED_ORIGINS=https://app.example.com",
        "MANAGEMENT_PORT=0"
})
@ActiveProfiles({"test", "prod"})
@Import(TestcontainersConfiguration.class)
class ProdProfileIntegrationTest {

    private final HttpClient client = HttpClient.newHttpClient();

    @LocalServerPort
    private int serverPort;

    @LocalManagementPort
    private int managementPort;

    @Autowired
    private JwtProvider jwtProvider;

    private HttpResponse<String> get(int port, String path, String bearer) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
        if (bearer != null) {
            request.header("Authorization", "Bearer " + bearer);
        }
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void 관리_포트는_서비스_포트와_분리되고_서비스_포트에는_Actuator가_없다() throws Exception {
        assertThat(managementPort).isNotEqualTo(serverPort);
        assertThat(get(serverPort, "/actuator/health", null).statusCode()).isEqualTo(404);
        assertThat(get(serverPort, "/actuator/prometheus", null).statusCode()).isEqualTo(404);
        // 서비스 API는 정상 동작
        assertThat(get(serverPort, "/api/stores", null).statusCode()).isEqualTo(200);
    }

    @Test
    void Swagger_UI와_API_명세는_비활성이다() throws Exception {
        for (String path : new String[]{"/swagger-ui.html", "/swagger-ui/index.html", "/v3/api-docs"}) {
            assertThat(get(serverPort, path, null).statusCode()).as(path).isEqualTo(404);
        }
    }

    @Test
    void 헬스체크는_상세_없이_UP만_응답하고_liveness_readiness를_제공한다() throws Exception {
        HttpResponse<String> health = get(managementPort, "/actuator/health", null);
        assertThat(health.statusCode()).isEqualTo(200);
        assertThat(health.body()).contains("\"status\":\"UP\"").doesNotContain("components").doesNotContain("db");

        assertThat(get(managementPort, "/actuator/health/liveness", null).statusCode()).isEqualTo(200);
        assertThat(get(managementPort, "/actuator/health/readiness", null).statusCode()).isEqualTo(200);
    }

    @Test
    void prometheus는_관리_포트에서_수집할_수_있다() throws Exception {
        HttpResponse<String> prometheus = get(managementPort, "/actuator/prometheus", null);
        assertThat(prometheus.statusCode()).isEqualTo(200);
        assertThat(prometheus.body()).contains("jvm_memory_used_bytes");
    }

    @Test
    void 그_밖의_Actuator_엔드포인트는_ADMIN_토큰이_있어도_존재하지_않는다() throws Exception {
        String admin = jwtProvider.createToken("admin", Role.ADMIN);
        for (String path : new String[]{"/actuator/env", "/actuator/info", "/actuator/metrics",
                "/actuator/heapdump", "/actuator/loggers", "/actuator/beans", "/actuator/configprops"}) {
            assertThat(get(managementPort, path, admin).statusCode()).as(path).isEqualTo(404);
            // 관리 포트에도 SecurityConfig가 적용됨 — health·prometheus 외 Actuator는 토큰 없으면 401
            assertThat(get(managementPort, path, null).statusCode()).as(path).isEqualTo(401);
        }
    }

    // prod에서는 환경변수(CORS_ALLOWED_ORIGINS)로 지정한 출처만 허용 (#61)
    @Test
    void CORS는_환경변수로_지정한_출처만_허용한다() throws Exception {
        HttpResponse<String> allowed = preflight("https://app.example.com");
        assertThat(allowed.statusCode()).isEqualTo(200);
        assertThat(allowed.headers().firstValue("Access-Control-Allow-Origin")).hasValue("https://app.example.com");
        // Refresh Token 쿠키(HttpOnly) 때문에 자격 증명 허용 — 허용 출처에만 (V15)
        assertThat(allowed.headers().firstValue("Access-Control-Allow-Credentials")).hasValue("true");

        // local 기본값(localhost:5173)은 prod에 적용되지 않음
        HttpResponse<String> denied = preflight("http://localhost:5173");
        assertThat(denied.statusCode()).isEqualTo(403);
        assertThat(denied.headers().firstValue("Access-Control-Allow-Origin")).isEmpty();
    }

    private HttpResponse<String> preflight(String origin) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + serverPort + "/api/orders"))
                .method("OPTIONS", HttpRequest.BodyPublishers.noBody())
                .header("Origin", origin)
                .header("Access-Control-Request-Method", "GET")
                .header("Access-Control-Request-Headers", "authorization")
                .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }
}
