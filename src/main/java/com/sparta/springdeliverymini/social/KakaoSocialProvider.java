package com.sparta.springdeliverymini.social;

import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.core.AuthenticationMethod;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Component;

// 카카오 로그인 — OAuth2 (OIDC 아님). 사용자 정보 API(/v2/user/me)의 id(회원번호)로 식별
// - 동의 항목(scope)을 요청하지 않음: 회원번호는 동의 없이 항상 오므로 닉네임·이메일을 받지 않음 (저장하지 않는 개인정보는 받지도 않음)
// - 클라이언트 인증은 client_secret_post — 카카오 콘솔에서 Client Secret을 "사용함"으로 둬야 함 (docs/local-verification.md 9장)
@Component
public class KakaoSocialProvider implements SocialProvider {

    @Override
    public String registrationId() {
        return "kakao";
    }

    @Override
    public ClientRegistration clientRegistration(String clientId, String clientSecret, String redirectUri) {
        return ClientRegistration.withRegistrationId(registrationId())
                .clientName("Kakao")
                .clientId(clientId)
                .clientSecret(clientSecret)
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_POST)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri(redirectUri)
                .authorizationUri("https://kauth.kakao.com/oauth/authorize")
                .tokenUri("https://kauth.kakao.com/oauth/token")
                .userInfoUri("https://kapi.kakao.com/v2/user/me")
                .userInfoAuthenticationMethod(AuthenticationMethod.HEADER)
                .userNameAttributeName("id")
                .build();
    }

    @Override
    public String providerUserId(OAuth2User user) {
        // 숫자(Long)로 오지만 공급자마다 형식이 달라 문자열로 저장
        return SocialProvider.requiredAttribute(user, "id");
    }
}
