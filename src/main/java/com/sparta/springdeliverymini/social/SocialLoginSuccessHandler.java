package com.sparta.springdeliverymini.social;

import com.sparta.springdeliverymini.controller.RefreshTokenCookies;
import com.sparta.springdeliverymini.exception.ApiException;
import com.sparta.springdeliverymini.exception.ErrorCode;
import com.sparta.springdeliverymini.service.IssuedTokens;
import com.sparta.springdeliverymini.service.SocialAccountService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;

// 공급자 인증이 끝난 뒤(코드 교환·사용자 정보 조회 성공) — 우리 회원으로 로그인하거나 기존 회원에 연결 (V22)
//
// - 로그인: SocialAccountService.login → 기존 로그인과 같은 Refresh Token 계열 발급 → 같은 HttpOnly 쿠키 → 프론트 ?result=login
//   Access Token은 URL·본문으로 주지 않음 (프론트가 POST /api/users/refresh로 받음 = 새로고침 후 복구와 같은 경로)
// - 연결: 시작 기록에 회원 id가 있을 때만 (SocialAuthorizationRequestResolver), 쿠키는 건드리지 않음 → ?result=linked
// - 업무 오류(이미 연결됨 등)는 예외로 끝내지 않고 ?error=code로 프론트에 알림
// Spring Security 인증 결과는 어디에도 저장하지 않음 (STATELESS — 이후 요청은 우리 JWT로 인증)
@Slf4j
@Component
@RequiredArgsConstructor
public class SocialLoginSuccessHandler implements AuthenticationSuccessHandler {

    private final SocialClientRegistrations registrations;
    private final SocialAccountService socialAccountService;
    private final RefreshTokenCookies refreshTokenCookies;
    private final SocialLoginRedirects redirects;

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response,
                                        Authentication authentication) throws IOException {
        OAuth2AuthenticationToken token = (OAuth2AuthenticationToken) authentication;
        String providerId = token.getAuthorizedClientRegistrationId();
        String linkIntent = linkIntentOf(request);
        try {
            SocialProvider provider = registrations.provider(providerId).orElseThrow(() ->
                    new ApiException(HttpStatus.NOT_FOUND,
                            ErrorCode.SOCIAL_PROVIDER_NOT_SUPPORTED, "지원하지 않는 소셜 로그인입니다."));
            String providerUserId = provider.providerUserId(token.getPrincipal());

            if (linkIntent == null) {
                IssuedTokens tokens = socialAccountService.login(providerId, providerUserId);
                response.addHeader(HttpHeaders.SET_COOKIE, refreshTokenCookies.create(tokens.refreshToken()).toString());
                redirects.loggedIn(response);
                return;
            }
            if (SocialAuthorizationRequestResolver.LINK_INVALID.equals(linkIntent)) {
                redirects.error(response, ErrorCode.SOCIAL_LINK_EXPIRED);
                return;
            }
            socialAccountService.link(Long.valueOf(linkIntent), providerId, providerUserId);
            redirects.linked(response, providerId);
        } catch (ApiException e) {
            log.warn("소셜 로그인 처리 실패 provider={}, link={}, status={}, code={}",
                    providerId, linkIntent != null, e.getStatus().value(), e.getCode());
            redirects.error(response, e.getCode() != null ? e.getCode() : ErrorCode.OAUTH2_FAILED);
        }
    }

    // 콜백 처리 중 저장소가 꺼내 둔 시작 기록의 연결 요청 값 (일반 로그인이면 null)
    private static String linkIntentOf(HttpServletRequest request) {
        Object removed = request.getAttribute(CookieAuthorizationRequestRepository.REMOVED_REQUEST_ATTRIBUTE);
        if (removed instanceof OAuth2AuthorizationRequest authorizationRequest) {
            return authorizationRequest.getAttribute(SocialAuthorizationRequestResolver.LINK_ATTRIBUTE);
        }
        return null;
    }
}
