package com.sparta.springdeliverymini.social;

import io.jsonwebtoken.io.Decoders;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Optional;

// 소셜 로그인 쿠키(로그인 시작 기록·연결 요청)의 변조 방지 서명 — HMAC-SHA256
// 서버가 세션을 쓰지 않으므로(STATELESS) 콜백까지 기억할 값을 쿠키에 두고, 서버만 만들 수 있게 서명함
//
// 키: jwt.secret에서 용도별로 파생 (HMAC(jwt.secret, "social-login-cookie"))
// - 환경변수를 하나 더 늘리지 않음 (운영 필수 변수·ProdProfileGuard 목록이 그대로)
// - 같은 키를 그대로 쓰지 않음: 이 쿠키 값이 어떤 경우에도 Access Token 서명으로 통하지 않도록 용도를 분리
// 값은 암호화하지 않음 (회원 id·공급자·state 정도라 비밀이 아님, HttpOnly라 JS도 못 읽음)
@Component
public class SocialCookieSigner {

    private static final String ALGORITHM = "HmacSHA256";
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

    private final SecretKeySpec key;

    public SocialCookieSigner(@Value("${jwt.secret}") String jwtSecret) {
        byte[] master = Decoders.BASE64.decode(jwtSecret);
        this.key = new SecretKeySpec(hmac(new SecretKeySpec(master, ALGORITHM),
                "social-login-cookie".getBytes(StandardCharsets.UTF_8)), ALGORITHM);
    }

    // 쿠키 값 = base64url(payload) "." base64url(HMAC) — 쿠키에 쓸 수 없는 문자가 없음
    public String sign(String payload) {
        String body = ENCODER.encodeToString(payload.getBytes(StandardCharsets.UTF_8));
        return body + "." + ENCODER.encodeToString(hmac(key, body.getBytes(StandardCharsets.US_ASCII)));
    }

    // 서명이 맞을 때만 payload. 형식 오류·변조는 모두 empty (이유를 구분해 알려주지 않음)
    public Optional<String> verify(String value) {
        if (value == null) {
            return Optional.empty();
        }
        int dot = value.indexOf('.');
        if (dot <= 0 || dot != value.lastIndexOf('.')) {
            return Optional.empty();
        }
        String body = value.substring(0, dot);
        try {
            byte[] expected = hmac(key, body.getBytes(StandardCharsets.US_ASCII));
            byte[] actual = DECODER.decode(value.substring(dot + 1));
            // 고정 시간 비교 — 서명을 한 바이트씩 맞혀 보는 시도(타이밍 공격)를 막음
            if (!MessageDigest.isEqual(expected, actual)) {
                return Optional.empty();
            }
            return Optional.of(new String(DECODER.decode(body), StandardCharsets.UTF_8));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    private static byte[] hmac(SecretKeySpec key, byte[] data) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(key);
            return mac.doFinal(data);
        } catch (GeneralSecurityException e) {
            // 모든 JVM이 HmacSHA256을 제공해야 함 (Java SE 명세)
            throw new IllegalStateException(e);
        }
    }
}
