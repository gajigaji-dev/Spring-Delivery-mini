package com.sparta.springdeliverymini.dto;

import com.sparta.springdeliverymini.entity.SocialAccount;

import java.time.LocalDateTime;
import java.util.List;

// 내 소셜 계정 목록 (V22) — GET /api/users/me/social-accounts
// hasPassword: false면 마지막 소셜 계정은 해제할 수 없음 (화면에서 해제 버튼을 막는 근거, 서버도 409로 막음)
// 소셜 회원 식별자(provider_user_id)는 응답에 넣지 않음 — 화면에 필요 없음
public record SocialAccountsResponse(boolean hasPassword, List<Item> accounts) {

    public record Item(String provider, LocalDateTime linkedAt) {

        public static Item from(SocialAccount account) {
            return new Item(account.getProvider(), account.getCreatedAt());
        }
    }
}
