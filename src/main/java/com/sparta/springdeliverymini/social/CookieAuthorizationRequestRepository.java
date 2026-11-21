package com.sparta.springdeliverymini.social;

import com.sparta.springdeliverymini.config.RefreshTokenProperties;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.security.oauth2.client.web.AuthorizationRequestRepository;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.util.WebUtils;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Set;

// OAuth2 로그인 시작 기록(state·nonce·PKCE 값 등)을 세션 대신 서명된 HttpOnly 쿠키에 보관 (V22)
//
// 기본 구현(HttpSessionOAuth2AuthorizationRequestRepository)을 쓰지 않는 이유
// - 서버는 STATELESS(JWT) — 소셜 로그인 때문에 세션(JSESSIONID)을 만들면 인스턴스가 여러 대일 때 고정 세션이 필요해짐
// - 쿠키는 로그인을 시작한 브라우저에만 있어 state 검증(로그인 CSRF 방어)이 그대로 성립
//
// 쿠키 속성
// - Path=/login/oauth2/code: 콜백 요청에만 실림
// - SameSite=Lax 고정: 콜백은 공급자 사이트에서 돌아오는 다른 사이트 이동(GET) → Strict면 쿠키가 실리지 않아 항상 실패
// - Max-Age 10분: 동의 화면에서 머무는 시간. 지나면 OAUTH2_REQUEST_EXPIRED로 처음부터 다시
// - 같은 브라우저에서 두 탭으로 동시에 시작하면 나중 것이 덮어써 먼저 시작한 탭은 실패 (state 불일치 → 다시 시도)
//
// 값은 JSON + HMAC 서명. Java 직렬화를 쓰지 않음 — 쿠키는 클라이언트가 보내는 값이라 역직렬화 공격 면을 두지 않도록
@Slf4j
@Component
@RequiredArgsConstructor
public class CookieAuthorizationRequestRepository implements AuthorizationRequestRepository<OAuth2AuthorizationRequest> {

    public static final String NAME = "oauth2_auth_request";
    static final String PATH = "/login/oauth2/code";
    static final Duration TTL = Duration.ofMinutes(10);
    // 콜백 처리 중 꺼낸 시작 기록 — 성공 처리기(SocialLoginSuccessHandler)가 연결 요청 여부를 읽음
    public static final String REMOVED_REQUEST_ATTRIBUTE = CookieAuthorizationRequestRepository.class.getName() + ".REMOVED";

    private final SocialCookieSigner signer;
    private final SocialLinkCookies linkCookies;
    private final RefreshTokenProperties cookieProperties;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    @Override
    public OAuth2AuthorizationRequest loadAuthorizationRequest(HttpServletRequest request) {
        Cookie cookie = WebUtils.getCookie(request, NAME);
        if (cookie == null) {
            return null;
        }
        return signer.verify(cookie.getValue()).map(this::parse).orElse(null);
    }

    @Override
    public void saveAuthorizationRequest(OAuth2AuthorizationRequest authorizationRequest,
                                         HttpServletRequest request, HttpServletResponse response) {
        if (authorizationRequest == null) {
            response.addHeader(HttpHeaders.SET_COOKIE, cookie("", Duration.ZERO).toString());
            return;
        }
        String json = objectMapper.writeValueAsString(new Stored(
                authorizationRequest.getAuthorizationUri(),
                authorizationRequest.getClientId(),
                authorizationRequest.getRedirectUri(),
                authorizationRequest.getScopes(),
                authorizationRequest.getState(),
                authorizationRequest.getAdditionalParameters(),
                authorizationRequest.getAttributes(),
                authorizationRequest.getAuthorizationRequestUri(),
                Instant.now(clock).getEpochSecond()));
        response.addHeader(HttpHeaders.SET_COOKIE, cookie(signer.sign(json), TTL).toString());
        // 연결 요청 쿠키는 한 번만 씀 — 시작 기록에 옮겼으면(또는 일반 로그인이면) 바로 지움
        if (WebUtils.getCookie(request, SocialLinkCookies.NAME) != null) {
            response.addHeader(HttpHeaders.SET_COOKIE, linkCookies.expire().toString());
        }
    }

    @Override
    public OAuth2AuthorizationRequest removeAuthorizationRequest(HttpServletRequest request,
                                                                 HttpServletResponse response) {
        OAuth2AuthorizationRequest loaded = loadAuthorizationRequest(request);
        if (WebUtils.getCookie(request, NAME) != null) {
            response.addHeader(HttpHeaders.SET_COOKIE, cookie("", Duration.ZERO).toString());
        }
        if (loaded != null) {
            request.setAttribute(REMOVED_REQUEST_ATTRIBUTE, loaded);
        }
        return loaded;
    }

    private OAuth2AuthorizationRequest parse(String json) {
        try {
            Stored stored = objectMapper.readValue(json, Stored.class);
            if (Instant.now(clock).isAfter(Instant.ofEpochSecond(stored.issuedAt()).plus(TTL))) {
                return null;
            }
            return OAuth2AuthorizationRequest.authorizationCode()
                    .authorizationUri(stored.authorizationUri())
                    .clientId(stored.clientId())
                    .redirectUri(stored.redirectUri())
                    .scopes(stored.scopes())
                    .state(stored.state())
                    .additionalParameters(stored.additionalParameters())
                    .attributes(stored.attributes())
                    .authorizationRequestUri(stored.authorizationRequestUri())
                    .build();
        } catch (JacksonException | IllegalArgumentException e) {
            // 서명은 맞는데 형식이 다름 = 배포 사이 형식 변경 등 → 시작 기록 없음으로 처리 (다시 로그인)
            log.warn("OAuth2 로그인 시작 기록 쿠키를 읽지 못함: {}", e.getClass().getSimpleName());
            return null;
        }
    }

    private ResponseCookie cookie(String value, Duration maxAge) {
        return ResponseCookie.from(NAME, value)
                .httpOnly(true)
                .secure(cookieProperties.cookieSecure())
                .sameSite("Lax")
                .path(PATH)
                .maxAge(maxAge)
                .build();
    }

    // 쿠키에 저장하는 형식 — 값은 모두 문자열·문자열 목록 (state, nonce, code_verifier, registration_id, 연결 요청)
    record Stored(String authorizationUri, String clientId, String redirectUri, Set<String> scopes, String state,
                  Map<String, Object> additionalParameters, Map<String, Object> attributes,
                  String authorizationRequestUri, long issuedAt) {
    }
}
