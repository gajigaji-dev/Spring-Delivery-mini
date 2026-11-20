package com.sparta.springdeliverymini.service;

import com.sparta.springdeliverymini.dto.LoginResponse;

// 로그인·재발급 결과 — Access Token은 응답 본문(LoginResponse), Refresh Token 원문은 컨트롤러가 HttpOnly 쿠키로만 내려보냄
// Refresh Token 원문은 이 객체 밖(DB·로그)에 남지 않음 (DB에는 해시만)
public record IssuedTokens(LoginResponse body, String refreshToken) {

    // 원문이 실수로 로그에 찍히지 않도록 toString에서 제외
    @Override
    public String toString() {
        return "IssuedTokens[body=" + body + ", refreshToken=***]";
    }
}
