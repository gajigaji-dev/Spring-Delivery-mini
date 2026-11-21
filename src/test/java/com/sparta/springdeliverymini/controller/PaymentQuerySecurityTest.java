package com.sparta.springdeliverymini.controller;

import com.sparta.springdeliverymini.config.SecurityConfig;
import com.sparta.springdeliverymini.dto.PaymentResponse;
import com.sparta.springdeliverymini.entity.OrderStatus;
import com.sparta.springdeliverymini.entity.PaymentMethod;
import com.sparta.springdeliverymini.entity.PaymentStatus;
import com.sparta.springdeliverymini.entity.Role;
import com.sparta.springdeliverymini.exception.ApiException;
import com.sparta.springdeliverymini.jwt.JwtProvider;
import com.sparta.springdeliverymini.service.PaymentIdempotencyService;
import com.sparta.springdeliverymini.service.PaymentService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 결제 단건 조회 API(#53)의 URL 권한 규칙(SecurityConfig)과 응답·에러 형식 검증
// Controller + Security 필터만 띄우고 Service는 Mock으로 대체 → DB 없이 실행 가능
@WebMvcTest(PaymentController.class)
@Import({SecurityConfig.class, JwtProvider.class})
class PaymentQuerySecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtProvider jwtProvider;

    @MockitoBean
    private PaymentService paymentService;

    @MockitoBean
    private PaymentIdempotencyService paymentIdempotencyService;

    private String bearer(String username, Role role) {
        return "Bearer " + jwtProvider.createToken(username, role);
    }

    @Test
    void CUSTOMER는_결제를_조회하면_200과_결제_정보를_받는다() throws Exception {
        given(paymentService.getPayment(1L, "customer1")).willReturn(new PaymentResponse(
                1L, 10L, 36000, PaymentMethod.CARD, OrderStatus.PAYMENT_COMPLETED,
                LocalDateTime.of(2026, 11, 15, 14, 5), PaymentStatus.COMPLETED, null, null, 0));

        mockMvc.perform(get("/api/payments/1")
                        .header(HttpHeaders.AUTHORIZATION, bearer("customer1", Role.CUSTOMER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.orderId").value(10))
                .andExpect(jsonPath("$.amount").value(36000))
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.orderStatus").value("PAYMENT_COMPLETED"))
                .andExpect(jsonPath("$.paidAt").value("2026-11-15T14:05:00"))
                // 수수료·정산액은 CUSTOMER 응답에 포함하지 않음
                .andExpect(jsonPath("$.feeAmount").doesNotExist())
                .andExpect(jsonPath("$.settlementAmount").doesNotExist());
    }

    @Test
    void OWNER는_Security에서_403으로_막힌다() throws Exception {
        mockMvc.perform(get("/api/payments/1")
                        .header(HttpHeaders.AUTHORIZATION, bearer("owner1", Role.OWNER)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("권한이 없습니다."));

        verify(paymentService, never()).getPayment(anyLong(), any());
    }

    @Test
    void 토큰이_없으면_401() throws Exception {
        mockMvc.perform(get("/api/payments/1"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("인증이 필요합니다."));

        verify(paymentService, never()).getPayment(anyLong(), any());
    }

    @Test
    void 서비스의_404는_공통_에러_형식으로_응답한다() throws Exception {
        given(paymentService.getPayment(999L, "customer1"))
                .willThrow(new ApiException(HttpStatus.NOT_FOUND, "결제 내역이 없습니다."));

        mockMvc.perform(get("/api/payments/999")
                        .header(HttpHeaders.AUTHORIZATION, bearer("customer1", Role.CUSTOMER)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("결제 내역이 없습니다."));
    }
}
