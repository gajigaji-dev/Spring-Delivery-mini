package com.sparta.springdeliverymini.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

// 사장님 전환 신청 — 신청자는 요청 본문이 아니라 토큰의 회원
public record OwnerApplicationRequest(
        // 매장 이름과 같은 상한(50자) — 승인 후 그대로 매장 이름으로 쓰는 경우가 많음
        @NotBlank(message = "상호를 입력해야 합니다.")
        @Size(max = 50, message = "상호는 50자 이하여야 합니다.")
        String businessName
) {
}
