package com.sparta.springdeliverymini.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.StringUtils;

import java.net.URI;
import java.util.Map;

// application.yml의 social.* — 소셜 로그인 (V22, docs/architecture.md 소셜 로그인 장)
//
// frontend-callback-url: 로그인·연결 결과를 돌려보낼 프론트 주소 (?result=login|linked 또는 ?error=CODE를 붙임)
//   설정값으로만 정함 — 요청 파라미터로 받으면 임의 사이트로 보내는 open redirect가 됨
// providers.{id}.client-id / client-secret: 공급자 콘솔에서 발급한 키 (.env의 KAKAO_*·GOOGLE_*)
//   둘 다 비어 있으면 그 공급자는 꺼짐(버튼이 안 보이고 /oauth2/authorization/{id}는 404), 하나만 있으면 설정 실수로 보고 기동 실패
@ConfigurationProperties(prefix = "social")
public record SocialLoginProperties(String frontendCallbackUrl, Map<String, Client> providers) {

    public SocialLoginProperties {
        providers = providers == null ? Map.of() : Map.copyOf(providers);
        if (!StringUtils.hasText(frontendCallbackUrl)) {
            throw new IllegalArgumentException("social.frontend-callback-url이 비어 있습니다.");
        }
        URI uri;
        try {
            uri = URI.create(frontendCallbackUrl.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("social.frontend-callback-url 형식이 올바르지 않습니다: " + frontendCallbackUrl);
        }
        // 결과를 쿼리로 붙이므로 쿼리·조각이 없는 절대 주소만
        if (!("http".equals(uri.getScheme()) || "https".equals(uri.getScheme()))
                || uri.getHost() == null || uri.getQuery() != null || uri.getFragment() != null) {
            throw new IllegalArgumentException(
                    "social.frontend-callback-url은 쿼리 없는 http(s) 절대 주소여야 합니다: " + frontendCallbackUrl);
        }
        frontendCallbackUrl = frontendCallbackUrl.trim();
        providers.forEach((id, client) -> {
            if (client != null && client.partiallyConfigured()) {
                throw new IllegalArgumentException(
                        "social.providers." + id + "는 client-id와 client-secret을 모두 설정하거나 모두 비워야 합니다.");
            }
        });
    }

    public record Client(String clientId, String clientSecret) {

        public boolean configured() {
            return StringUtils.hasText(clientId) && StringUtils.hasText(clientSecret);
        }

        boolean partiallyConfigured() {
            return StringUtils.hasText(clientId) != StringUtils.hasText(clientSecret);
        }

        // 시크릿이 로그·예외 메시지에 찍히지 않도록
        @Override
        public String toString() {
            return "Client[clientId=" + clientId + ", clientSecret=***]";
        }
    }
}
