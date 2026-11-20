package com.sparta.springdeliverymini.dto;

// 로그인·재발급 응답
// accessToken: 발급된 JWT, tokenType: 헤더에 붙일 접두어("Bearer")
// expiresIn: Access Token 수명(초) — 클라이언트가 만료 직전에 미리 재발급할 수 있도록 (필드 추가만, 기존 필드 유지)
// Refresh Token은 본문에 넣지 않음 → HttpOnly 쿠키(Set-Cookie)로만 전달 (JS에서 읽을 수 없게)
public record LoginResponse(String accessToken, String tokenType, long expiresIn) {

    // tokenType을 항상 "Bearer"로 고정해서 생성
    public static LoginResponse bearer(String accessToken, long expiresIn) {
        return new LoginResponse(accessToken, "Bearer", expiresIn);
    }
}
