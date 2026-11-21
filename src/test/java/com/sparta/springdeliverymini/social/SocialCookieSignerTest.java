package com.sparta.springdeliverymini.social;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

// 소셜 로그인 쿠키 서명 (V22)
class SocialCookieSignerTest {

    private static final String SECRET = "dGVzdC1vbmx5LXNlY3JldC1rZXktZm9yLXNwcmluZy1kZWxpdmVyeS1taW5pLWp3dC0yNTZiaXQ=";

    private final SocialCookieSigner signer = new SocialCookieSigner(SECRET);

    @Test
    void 서명한_값은_그대로_되돌려_받는다() {
        String signed = signer.sign("{\"state\":\"abc\"}:한글");

        assertThat(signed).matches("[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+");
        assertThat(signer.verify(signed)).contains("{\"state\":\"abc\"}:한글");
    }

    @Test
    void 본문이나_서명을_바꾸면_거부한다() {
        String signed = signer.sign("12:kakao:1700000000");
        String otherBody = signer.sign("13:kakao:1700000000").split("\\.")[0];

        assertThat(signer.verify(otherBody + "." + signed.split("\\.")[1])).isEmpty();
        assertThat(signer.verify(signed + "x")).isEmpty();
        assertThat(signer.verify("no-dot")).isEmpty();
        assertThat(signer.verify("a.b.c")).isEmpty();
        assertThat(signer.verify(null)).isEmpty();
    }

    @Test
    void 키가_다르면_서명이_통하지_않는다() {
        SocialCookieSigner other = new SocialCookieSigner(
                "b3RoZXItdGVzdC1zZWNyZXQta2V5LWZvci1zcHJpbmctZGVsaXZlcnktbWluaS0yNTZiaXQ=");

        assertThat(other.verify(signer.sign("12:kakao:1700000000"))).isEmpty();
    }
}
