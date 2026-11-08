package com.sparta.springdeliverymini.dto;

import com.sparta.springdeliverymini.entity.Role;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

// 회원가입 요청
// 검증에 실패하면 GlobalExceptionHandler가 "필드명: 메시지" 형태로 400 응답
public record SignupRequest(
        @NotBlank(message = "아이디를 입력해야 합니다.")
        @Size(min = 4, max = 20, message = "아이디는 4~20자여야 합니다.")
        String username,

        // BCrypt는 72바이트까지만 처리하므로 상한을 둠
        @NotBlank(message = "비밀번호를 입력해야 합니다.")
        @Size(min = 8, max = 72, message = "비밀번호는 8~72자여야 합니다.")
        String password,

        // CUSTOMER(고객) 또는 OWNER(사장님) — ADMIN은 enum에는 있지만 가입 불가 (UserService에서 400)
        // 그 외 문자열은 JSON → enum 변환에 실패해 HttpMessageNotReadableException → 400
        @NotNull(message = "role은 CUSTOMER 또는 OWNER를 입력해야 합니다.")
        Role role
) {
}
