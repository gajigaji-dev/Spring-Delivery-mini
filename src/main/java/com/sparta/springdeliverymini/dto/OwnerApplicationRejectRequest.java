package com.sparta.springdeliverymini.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

// 사장님 전환 신청 거절 — 사유는 신청자 화면에 그대로 보이므로 필수
public record OwnerApplicationRejectRequest(
        @NotBlank(message = "거절 사유를 입력해야 합니다.")
        @Size(max = 255, message = "거절 사유는 255자 이하여야 합니다.")
        String reason
) {
}
