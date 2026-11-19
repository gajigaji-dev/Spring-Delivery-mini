package com.sparta.springdeliverymini.config;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// CORS 허용 출처 설정값 검사 (#61) — 잘못된 값은 기동 시점(바인딩)에 실패
class CorsPropertiesTest {

    @Test
    void 출처_목록의_앞뒤_공백과_빈_값은_정리한다() {
        CorsProperties properties = new CorsProperties(
                List.of(" http://localhost:5173 ", "", "https://app.example.com:8443"));

        assertThat(properties.allowedOrigins())
                .containsExactly("http://localhost:5173", "https://app.example.com:8443");
    }

    @Test
    void 설정이_없으면_빈_목록으로_다른_출처를_모두_막는다() {
        assertThat(new CorsProperties(null).allowedOrigins()).isEmpty();
    }

    @Test
    void 와일드카드_출처는_기동을_막는다() {
        for (String origin : List.of("*", "https://*.example.com")) {
            assertThatThrownBy(() -> new CorsProperties(List.of(origin)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("와일드카드");
        }
    }

    @Test
    void 경로나_끝_슬래시가_있거나_scheme이_없는_출처는_기동을_막는다() {
        for (String origin : List.of("http://localhost:5173/", "https://app.example.com/app", "localhost:5173",
                "ftp://app.example.com")) {
            assertThatThrownBy(() -> new CorsProperties(List.of(origin)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("scheme://host[:port]");
        }
    }
}
