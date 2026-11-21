package com.sparta.springdeliverymini.social;

import com.sparta.springdeliverymini.config.RefreshTokenProperties;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;
import org.springframework.web.util.WebUtils;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

// 기존 회원의 소셜 계정 연결 요청 쿠키 (V22) — "이 브라우저의 로그인한 회원 X가 공급자 P를 연결하려 함"
//
// 흐름: POST /api/users/me/social-accounts/{provider}/link (Bearer 인증) → 이 쿠키 발급
//      → 프론트가 /oauth2/authorization/{provider}?mode=link 로 이동 → 쿠키를 읽어 로그인 시작 기록에 회원 id를 넣고 쿠키는 지움
// 왜 쿠키인가: 공급자로 가는 이동은 페이지 전체 이동이라 Authorization 헤더(메모리의 Access Token)를 실을 수 없음
//   - URL에 연결 티켓을 넣으면 기록·로그에 남고, 그 티켓을 얻은 사람이 자기 소셜 계정을 남의 회원에 연결할 수 있음
//   - HttpOnly 쿠키는 연결 요청을 만든 브라우저에만 있고 JS로 읽거나 옮길 수 없음
// 서명(SocialCookieSigner)으로 회원 id 변조를 막고, 5분 지나면 무효
// SameSite·Secure는 Refresh 쿠키와 같은 설정 — 프론트(5173)와 API(8090)가 같은 사이트일 때 Strict로도 전송됨
@Component
@RequiredArgsConstructor
public class SocialLinkCookies {

    public static final String NAME = "social_link";
    static final String PATH = "/oauth2/authorization";
    static final Duration TTL = Duration.ofMinutes(5);

    private final SocialCookieSigner signer;
    private final RefreshTokenProperties cookieProperties;
    private final Clock clock;

    public ResponseCookie create(Long userId, String provider) {
        String payload = userId + ":" + provider + ":" + Instant.now(clock).getEpochSecond();
        return base(signer.sign(payload)).maxAge(TTL).build();
    }

    public ResponseCookie expire() {
        return base("").maxAge(Duration.ZERO).build();
    }

    // 연결할 회원 id — 쿠키가 없거나 서명 불일치·만료·다른 공급자면 empty
    public Optional<Long> read(HttpServletRequest request, String provider) {
        Cookie cookie = WebUtils.getCookie(request, NAME);
        if (cookie == null) {
            return Optional.empty();
        }
        return signer.verify(cookie.getValue()).flatMap(payload -> parse(payload, provider));
    }

    private Optional<Long> parse(String payload, String provider) {
        String[] parts = payload.split(":");
        if (parts.length != 3 || !parts[1].equals(provider)) {
            return Optional.empty();
        }
        try {
            long userId = Long.parseLong(parts[0]);
            Instant issuedAt = Instant.ofEpochSecond(Long.parseLong(parts[2]));
            if (Instant.now(clock).isAfter(issuedAt.plus(TTL))) {
                return Optional.empty();
            }
            return Optional.of(userId);
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    private ResponseCookie.ResponseCookieBuilder base(String value) {
        return ResponseCookie.from(NAME, value)
                .httpOnly(true)
                .secure(cookieProperties.cookieSecure())
                .sameSite(cookieProperties.cookieSameSite())
                .path(PATH);
    }
}
