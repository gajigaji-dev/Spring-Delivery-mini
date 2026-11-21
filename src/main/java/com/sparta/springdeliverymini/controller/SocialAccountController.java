package com.sparta.springdeliverymini.controller;

import com.sparta.springdeliverymini.dto.SocialAccountsResponse;
import com.sparta.springdeliverymini.service.SocialAccountService;
import com.sparta.springdeliverymini.social.SocialClientRegistrations;
import com.sparta.springdeliverymini.social.SocialLinkCookies;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

// 소셜 로그인 보조 API (V22) — 로그인 자체는 /oauth2/authorization/{provider} (Spring Security 필터), docs/api.md 소셜 로그인 장
@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
public class SocialAccountController {

    private final SocialAccountService socialAccountService;
    private final SocialClientRegistrations registrations;
    private final SocialLinkCookies linkCookies;

    // 사용 가능한 공급자 (키가 설정된 것만) — 로그인 화면 버튼 표시용, 토큰 없이 호출 (SecurityConfig permitAll)
    @GetMapping("/social/providers")
    public List<String> providers() {
        return registrations.enabledIds();
    }

    @GetMapping("/me/social-accounts")
    public SocialAccountsResponse mySocialAccounts(Authentication authentication) {
        return socialAccountService.getMine(authentication.getName());
    }

    // 연결 시작 — 연결 요청 쿠키(5분)만 발급, 204. 프론트는 이어서 /oauth2/authorization/{provider}?mode=link 로 이동
    // 쿠키를 받으려면 프론트가 credentials: 'include'로 호출해야 함
    @PostMapping("/me/social-accounts/{provider}/link")
    public ResponseEntity<Void> startLink(@PathVariable String provider, Authentication authentication) {
        Long userId = socialAccountService.startLink(authentication.getName(), provider);
        return ResponseEntity.noContent()
                .header(HttpHeaders.SET_COOKIE, linkCookies.create(userId, provider).toString())
                .build();
    }

    @DeleteMapping("/me/social-accounts/{provider}")
    public ResponseEntity<Void> unlink(@PathVariable String provider, Authentication authentication) {
        socialAccountService.unlink(authentication.getName(), provider);
        return ResponseEntity.noContent().build();
    }
}
