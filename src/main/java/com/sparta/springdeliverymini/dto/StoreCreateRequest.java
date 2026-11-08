package com.sparta.springdeliverymini.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

// 매장 생성 요청 — 점주는 요청 본문이 아니라 토큰의 아이디로 정함
public record StoreCreateRequest(
        @NotBlank(message = "매장 이름을 입력해야 합니다.")
        @Size(max = 50, message = "매장 이름은 50자 이하여야 합니다.")
        String name
) {
}
