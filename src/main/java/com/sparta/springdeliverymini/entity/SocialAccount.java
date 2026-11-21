package com.sparta.springdeliverymini.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

// 회원에 연결된 소셜 계정 (V22) — 공급자(kakao·google)가 준 회원 식별자로 우리 회원을 찾음
// 연결 후 바뀌는 값이 없어 불변 (연결 해제 = 행 삭제)
// 중복 방지는 DB 제약: (provider, provider_user_id) — 같은 소셜 계정 중복 가입, (user_id, provider) — 회원당 공급자별 1개
@Entity
@Table(name = "social_accounts")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SocialAccount extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private Long userId;

    // 공급자 registrationId (social.SocialProvider.registrationId) — 문자열로 둬서 공급자 추가 시 enum·마이그레이션이 필요 없음
    @Column(nullable = false, length = 20, updatable = false)
    private String provider;

    @Column(nullable = false, length = 255, updatable = false)
    private String providerUserId;

    public SocialAccount(Long userId, String provider, String providerUserId) {
        if (userId == null || provider == null || provider.isBlank()
                || providerUserId == null || providerUserId.isBlank()) {
            throw new IllegalArgumentException("소셜 계정 연결에 필요한 값이 없습니다.");
        }
        if (providerUserId.length() > 255) {
            throw new IllegalArgumentException("소셜 회원 식별자가 너무 깁니다.");
        }
        this.userId = userId;
        this.provider = provider;
        this.providerUserId = providerUserId;
    }
}
