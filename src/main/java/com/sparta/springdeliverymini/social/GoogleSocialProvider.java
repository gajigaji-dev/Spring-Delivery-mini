package com.sparta.springdeliverymini.social;

import org.springframework.security.config.oauth2.client.CommonOAuth2Provider;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Component;

// 구글 로그인 — OpenID Connect. ID 토큰의 sub(구글 계정 고유 id)로 식별
// - scope는 openid만: 이메일·프로필을 받지 않음 (저장하지 않는 개인정보)
// - ID 토큰 서명·nonce·aud 검증은 Spring Security(OidcAuthorizationCodeAuthenticationProvider)가 함
@Component
public class GoogleSocialProvider implements SocialProvider {

    @Override
    public String registrationId() {
        return "google";
    }

    @Override
    public ClientRegistration clientRegistration(String clientId, String clientSecret, String redirectUri) {
        // 엔드포인트·JWK 주소는 Spring Security 기본값 사용, scope·콜백 주소만 덮어씀
        return CommonOAuth2Provider.GOOGLE.getBuilder(registrationId())
                .clientId(clientId)
                .clientSecret(clientSecret)
                .redirectUri(redirectUri)
                .scope("openid")
                .build();
    }

    @Override
    public String providerUserId(OAuth2User user) {
        return SocialProvider.requiredAttribute(user, "sub");
    }
}
