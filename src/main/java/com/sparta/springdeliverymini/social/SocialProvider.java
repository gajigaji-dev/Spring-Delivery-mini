package com.sparta.springdeliverymini.social;

import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.user.OAuth2User;

// 소셜 로그인 공급자 1개의 차이점만 모은 확장 지점 (V22) — docs/architecture.md 소셜 로그인 장
// 공급자 추가 = 이 인터페이스 구현(@Component) 1개 + application.yml social.providers.{id} 키 설정
// (흐름·계정 연결·토큰 발급·쿠키는 공통 코드라 건드리지 않음. DB도 provider가 문자열이라 마이그레이션 불필요)
public interface SocialProvider {

    // URL·DB에 쓰는 공급자 id (/oauth2/authorization/{id}, social_accounts.provider) — 소문자, 20자 이하
    String registrationId();

    // 공급자 엔드포인트·인증 방식 — 키와 콜백 주소만 밖에서 받음
    ClientRegistration clientRegistration(String clientId, String clientSecret, String redirectUri);

    // 공급자가 준 회원 식별자 — 바뀌지 않는 값이어야 함 (이메일·닉네임은 바뀔 수 있어 쓰지 않음)
    String providerUserId(OAuth2User user);

    // 공통: 식별자 속성이 없으면 로그인 실패로 처리 (공급자 응답 형식이 바뀐 경우)
    static String requiredAttribute(OAuth2User user, String name) {
        Object value = user.getAttributes().get(name);
        if (value == null || value.toString().isBlank()) {
            throw new OAuth2AuthenticationException(new OAuth2Error("invalid_user_info"),
                    "공급자 응답에 회원 식별자(" + name + ")가 없습니다.");
        }
        return value.toString();
    }
}
