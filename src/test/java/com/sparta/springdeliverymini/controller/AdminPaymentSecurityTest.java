package com.sparta.springdeliverymini.controller;

import com.sparta.springdeliverymini.config.SecurityConfig;
import com.sparta.springdeliverymini.entity.Role;
import com.sparta.springdeliverymini.exception.InvalidPaymentStatusException;
import com.sparta.springdeliverymini.jwt.JwtProvider;
import com.sparta.springdeliverymini.service.AdminPaymentService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 관리자 환불 API의 URL 단위 권한 규칙(SecurityConfig의 /api/admin/**)과 예외 변환 검증
// Controller + Security 필터만 띄우고 Service는 Mock으로 대체 → DB 없이 실행 가능
@WebMvcTest(AdminPaymentController.class)
@Import({SecurityConfig.class, JwtProvider.class})
class AdminPaymentSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtProvider jwtProvider;

    @MockitoBean
    private AdminPaymentService adminPaymentService;

    private String bearer(String username, Role role) {
        return "Bearer " + jwtProvider.createToken(username, role);
    }

    @Test
    void 토큰이_없으면_401() throws Exception {
        mockMvc.perform(patch("/api/admin/payments/1/refund"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(adminPaymentService);
    }

    @Test
    void OWNER와_CUSTOMER는_403이고_Service까지_가지_않는다() throws Exception {
        for (Role role : new Role[]{Role.OWNER, Role.CUSTOMER}) {
            mockMvc.perform(patch("/api/admin/payments/1/refund")
                            .header(HttpHeaders.AUTHORIZATION, bearer("user1", role)))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.message").value("권한이 없습니다."));
        }
        verifyNoInteractions(adminPaymentService);
    }

    @Test
    void ADMIN은_환불할_수_있다() throws Exception {
        mockMvc.perform(patch("/api/admin/payments/1/refund")
                        .header(HttpHeaders.AUTHORIZATION, bearer("admin", Role.ADMIN)))
                .andExpect(status().isOk());

        verify(adminPaymentService).refund(1L, "admin");
    }

    @Test
    void 환불_규칙_위반은_409() throws Exception {
        given(adminPaymentService.refund(anyLong(), anyString()))
                .willThrow(new InvalidPaymentStatusException("완료된 결제만 환불할 수 있습니다. 현재 상태: REFUNDED"));

        mockMvc.perform(patch("/api/admin/payments/1/refund")
                        .header(HttpHeaders.AUTHORIZATION, bearer("admin", Role.ADMIN)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("완료된 결제만 환불할 수 있습니다. 현재 상태: REFUNDED"));
    }
}
