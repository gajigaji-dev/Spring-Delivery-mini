package com.sparta.springdeliverymini.social;

import com.sparta.springdeliverymini.config.SocialLoginProperties;
import com.sparta.springdeliverymini.exception.ErrorCode;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.IOException;

// OAuth2 콜백이 끝난 뒤 프론트로 돌려보내는 주소 — 설정값(social.frontend-callback-url)에만 쿼리를 붙임
// 성공: ?result=login | ?result=linked&provider=kakao / 실패: ?error={ErrorCode}
// 토큰은 URL에 넣지 않음 — 로그인 성공 시 Refresh 쿠키만 심고 프론트가 POST /api/users/refresh로 Access를 받음
@Component
@RequiredArgsConstructor
public class SocialLoginRedirects {

    private final SocialLoginProperties properties;

    public void loggedIn(HttpServletResponse response) throws IOException {
        response.sendRedirect(builder().queryParam("result", "login").toUriString());
    }

    public void linked(HttpServletResponse response, String provider) throws IOException {
        response.sendRedirect(builder().queryParam("result", "linked").queryParam("provider", provider).toUriString());
    }

    public void error(HttpServletResponse response, ErrorCode code) throws IOException {
        response.sendRedirect(builder().queryParam("error", code.name()).toUriString());
    }

    private UriComponentsBuilder builder() {
        return UriComponentsBuilder.fromUriString(properties.frontendCallbackUrl());
    }
}
