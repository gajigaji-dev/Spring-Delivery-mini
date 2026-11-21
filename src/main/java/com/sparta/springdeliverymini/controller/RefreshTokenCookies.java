package com.sparta.springdeliverymini.controller;

import com.sparta.springdeliverymini.config.RefreshTokenProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

import java.time.Duration;

// Refresh Token 쿠키 생성·삭제 — 속성은 auth.refresh-token.* (config.RefreshTokenProperties)
// - HttpOnly: 브라우저 JS(XSS 포함)가 읽을 수 없음 → Refresh Token은 응답 본문에 넣지 않고 쿠키로만 전달
// - Path=/api/users: 로그인·재발급·로그아웃 경로에만 실림 (다른 API 요청마다 Refresh Token을 보내지 않도록)
// - SameSite·Secure: 설정값 (기본 Strict·Secure) — 다른 사이트에서 위조한 요청(CSRF)에 쿠키가 실리지 않게
// - Max-Age: Refresh Token 수명과 같음 → 서버 기록이 만료되는 시점에 브라우저도 쿠키를 버림
@Component
@RequiredArgsConstructor
public class RefreshTokenCookies {

    public static final String NAME = "refresh_token";
    static final String PATH = "/api/users";

    private final RefreshTokenProperties properties;

    public ResponseCookie create(String refreshToken) {
        return base(refreshToken).maxAge(properties.ttl()).build();
    }

    // 로그아웃·재발급 실패 시 브라우저의 쿠키 삭제 (같은 이름·Path·속성에 Max-Age=0)
    public ResponseCookie expire() {
        return base("").maxAge(Duration.ZERO).build();
    }

    private ResponseCookie.ResponseCookieBuilder base(String value) {
        return ResponseCookie.from(NAME, value)
                .httpOnly(true)
                .secure(properties.cookieSecure())
                .sameSite(properties.cookieSameSite())
                .path(PATH);
    }
}
