package com.sparta.springdeliverymini.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.Set;

// application.yml의 auth.refresh-token.* — Refresh Token 수명·쿠키 속성 (service.AuthTokenService, controller.RefreshTokenCookies)
// 값이 없거나 잘못되면 바인딩 단계에서 예외 → 애플리케이션 기동 실패
//
// ttl: Refresh Token 수명. 재발급할 때마다 그 시점부터 다시 ttl (ttl 동안 한 번도 재발급하지 않으면 재로그인)
// absolute-ttl: 로그인 시점부터 세는 최대 수명 (V16). 계속 재발급해도 이 기간이 지나면 재로그인 — ttl 이상이어야 함
// cookie-secure: HTTPS에서만 쿠키 전송. localhost는 브라우저가 보안 출처로 취급해 http에서도 동작하므로 기본 true
// cookie-same-site: Strict(기본) | Lax | None
//   - 프론트엔드와 API가 같은 사이트(등록 도메인이 같음, 포트 무관 — 예: localhost:5173 ↔ localhost:8090)면 Strict로 충분
//   - 다른 사이트에 배포하면 None이 필요하고, 그때는 cookie-secure=true 필수 (브라우저가 Secure 없는 None 쿠키를 거부)
@ConfigurationProperties(prefix = "auth.refresh-token")
public record RefreshTokenProperties(Duration ttl, Duration absoluteTtl, boolean cookieSecure, String cookieSameSite) {

    private static final Set<String> SAME_SITE_VALUES = Set.of("Strict", "Lax", "None");

    public RefreshTokenProperties {
        if (ttl == null || ttl.isNegative() || ttl.isZero()) {
            throw new IllegalArgumentException("auth.refresh-token.ttl은 0보다 커야 합니다: " + ttl);
        }
        // ttl보다 짧으면 ttl 설정이 의미 없어짐 (첫 토큰부터 절대 만료에 잘림) → 설정 실수로 보고 기동 실패
        if (absoluteTtl == null || absoluteTtl.compareTo(ttl) < 0) {
            throw new IllegalArgumentException(
                    "auth.refresh-token.absolute-ttl은 ttl 이상이어야 합니다: ttl=" + ttl + ", absolute-ttl=" + absoluteTtl);
        }
        if (cookieSameSite == null || !SAME_SITE_VALUES.contains(cookieSameSite)) {
            throw new IllegalArgumentException(
                    "auth.refresh-token.cookie-same-site는 Strict, Lax, None 중 하나여야 합니다: " + cookieSameSite);
        }
        if ("None".equals(cookieSameSite) && !cookieSecure) {
            throw new IllegalArgumentException("auth.refresh-token.cookie-same-site=None이면 cookie-secure=true여야 합니다.");
        }
    }
}
