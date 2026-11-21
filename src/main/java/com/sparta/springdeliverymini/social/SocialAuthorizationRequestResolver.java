package com.sparta.springdeliverymini.social;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;

// /oauth2/authorization/{provider}[?mode=link] → 공급자 동의 화면으로 보낼 요청 생성 (V22)
// 기본 구현에 두 가지를 더함
// 1. 꺼진(키 없는)·없는 공급자면 null → 필터가 요청을 그냥 넘겨 404 (기본 구현은 예외 → 500)
// 2. mode=link면 연결 요청 쿠키를 읽어 시작 기록 속성(social_link)에 연결할 회원 id를 넣음
//    쿠키가 없거나 무효면 "invalid"를 넣어 콜백에서 SOCIAL_LINK_EXPIRED로 끝냄
//    — 무효라고 일반 로그인으로 진행하면 연결하려던 사용자가 모르는 새 계정으로 로그인됨
public class SocialAuthorizationRequestResolver implements OAuth2AuthorizationRequestResolver {

    public static final String BASE_URI = DefaultOAuth2AuthorizationRequestResolver.DEFAULT_AUTHORIZATION_REQUEST_BASE_URI;
    public static final String LINK_ATTRIBUTE = "social_link";
    public static final String LINK_INVALID = "invalid";

    private final SocialClientRegistrations registrations;
    private final SocialLinkCookies linkCookies;
    private final DefaultOAuth2AuthorizationRequestResolver delegate;

    public SocialAuthorizationRequestResolver(SocialClientRegistrations registrations, SocialLinkCookies linkCookies) {
        this.registrations = registrations;
        this.linkCookies = linkCookies;
        this.delegate = new DefaultOAuth2AuthorizationRequestResolver(registrations, BASE_URI);
    }

    @Override
    public OAuth2AuthorizationRequest resolve(HttpServletRequest request) {
        String registrationId = registrationIdOf(request);
        if (registrationId == null || registrations.findByRegistrationId(registrationId) == null) {
            return null;
        }
        return withLinkIntent(request, registrationId, delegate.resolve(request));
    }

    @Override
    public OAuth2AuthorizationRequest resolve(HttpServletRequest request, String clientRegistrationId) {
        if (registrations.findByRegistrationId(clientRegistrationId) == null) {
            return null;
        }
        return withLinkIntent(request, clientRegistrationId, delegate.resolve(request, clientRegistrationId));
    }

    private OAuth2AuthorizationRequest withLinkIntent(HttpServletRequest request, String registrationId,
                                                      OAuth2AuthorizationRequest base) {
        if (base == null || !"link".equals(request.getParameter("mode"))) {
            return base;
        }
        String intent = linkCookies.read(request, registrationId).map(String::valueOf).orElse(LINK_INVALID);
        return OAuth2AuthorizationRequest.from(base)
                .attributes(attributes -> attributes.put(LINK_ATTRIBUTE, intent))
                .build();
    }

    // 경로 끝의 {provider} — /oauth2/authorization/{provider} 외에는 null
    private static String registrationIdOf(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        String prefix = BASE_URI + "/";
        if (!path.startsWith(prefix)) {
            return null;
        }
        String id = path.substring(prefix.length());
        return id.isEmpty() || id.contains("/") ? null : id;
    }
}
