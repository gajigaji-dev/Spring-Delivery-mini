package com.sparta.springdeliverymini.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;
import java.util.regex.Pattern;

// application.yml의 cors.* — 브라우저(프론트엔드)에서 API를 호출할 수 있는 출처 (SecurityConfig, docs/operations.md 7장)
// 환경변수 CORS_ALLOWED_ORIGINS(쉼표 구분)로 지정. 값이 잘못되면 바인딩 단계에서 예외 → 애플리케이션 기동 실패
// 빈 목록이면 다른 출처의 브라우저 요청을 모두 막음 (같은 출처 요청·curl 등 브라우저 밖 요청은 영향 없음)
@ConfigurationProperties(prefix = "cors")
public record CorsProperties(List<String> allowedOrigins) {

    // scheme://host[:port] 형식만 허용 — 브라우저가 보내는 Origin 헤더에는 경로·끝 슬래시가 없으므로
    // "http://localhost:5173/"처럼 적으면 어떤 요청과도 일치하지 않아 조용히 모두 막힘 → 기동 시점에 드러나도록 거부
    private static final Pattern ORIGIN = Pattern.compile("^https?://[^/\\s*?#]+$");

    public CorsProperties {
        allowedOrigins = allowedOrigins == null ? List.of() : allowedOrigins.stream()
                .map(String::trim)
                .filter(origin -> !origin.isEmpty())
                .toList();
        for (String origin : allowedOrigins) {
            // 와일드카드(*)는 쓰지 않음: 모든 출처 허용은 토큰을 가진 사용자의 브라우저에서 어느 사이트든 API를 호출할 수 있게 함
            if (origin.contains("*")) {
                throw new IllegalArgumentException("cors.allowed-origins에 와일드카드(*)는 사용할 수 없습니다: " + origin);
            }
            if (!ORIGIN.matcher(origin).matches()) {
                throw new IllegalArgumentException(
                        "cors.allowed-origins는 scheme://host[:port] 형식이어야 합니다 (경로·끝 슬래시 없이): " + origin);
            }
        }
    }
}
