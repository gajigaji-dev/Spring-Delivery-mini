package com.sparta.springdeliverymini.controller;

import com.sparta.springdeliverymini.config.SecurityConfig;
import com.sparta.springdeliverymini.entity.Role;
import com.sparta.springdeliverymini.exception.InvalidPaymentStatusException;
import com.sparta.springdeliverymini.jwt.JwtProvider;
import com.sparta.springdeliverymini.logging.TraceIdFilter;
import com.sparta.springdeliverymini.service.AdminPaymentService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 공통 에러 응답 형식(ApiErrorResponse)과 traceId 연결 검증
// Security 필터(401/403)·GlobalExceptionHandler(409/500)·Spring MVC 표준 예외(404/405)가 모두 같은 형식으로 응답하고,
// 본문 traceId = 응답 헤더 X-Trace-Id = 서버 로그 traceId 인지 확인
@WebMvcTest(AdminPaymentController.class)
@Import({SecurityConfig.class, JwtProvider.class, TraceIdFilter.class})
class ErrorResponseFormatTest {

    private static final String REFUND_URL = "/api/admin/payments/1/refund";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtProvider jwtProvider;

    @MockitoBean
    private AdminPaymentService adminPaymentService;

    private String adminBearer() {
        return "Bearer " + jwtProvider.createToken("admin", Role.ADMIN);
    }

    private static String traceIdHeader(MvcResult result) {
        return result.getResponse().getHeader(TraceIdFilter.TRACE_ID_HEADER);
    }

    @Test
    void 인증_실패_401도_공통_형식이고_traceId가_헤더와_같다() throws Exception {
        MvcResult result = mockMvc.perform(patch(REFUND_URL))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.error").value("Unauthorized"))
                .andExpect(jsonPath("$.message").value("인증이 필요합니다.")) // 기존 message 유지
                .andExpect(jsonPath("$.path").value(REFUND_URL))
                .andReturn();

        assertThat(traceIdHeader(result)).isNotBlank();
        assertThat(result.getResponse().getContentAsString()).contains("\"traceId\":\"" + traceIdHeader(result) + "\"");
    }

    @Test
    void 권한_없음_403도_공통_형식이다() throws Exception {
        mockMvc.perform(patch(REFUND_URL)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + jwtProvider.createToken("owner", Role.OWNER)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403))
                .andExpect(jsonPath("$.error").value("Forbidden"))
                .andExpect(jsonPath("$.message").value("권한이 없습니다."))
                .andExpect(jsonPath("$.traceId").isNotEmpty());
    }

    @Test
    void 상태_전이_위반_409는_클라이언트가_보낸_traceId를_그대로_돌려준다() throws Exception {
        given(adminPaymentService.refund(anyLong(), anyString()))
                .willThrow(new InvalidPaymentStatusException("완료된 결제만 환불할 수 있습니다. 현재 상태: REFUNDED"));

        mockMvc.perform(patch(REFUND_URL)
                        .header(HttpHeaders.AUTHORIZATION, adminBearer())
                        .header(TraceIdFilter.TRACE_ID_HEADER, "client-trace-0001"))
                .andExpect(status().isConflict())
                .andExpect(header().string(TraceIdFilter.TRACE_ID_HEADER, "client-trace-0001"))
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.error").value("Conflict"))
                .andExpect(jsonPath("$.message").value("완료된 결제만 환불할 수 있습니다. 현재 상태: REFUNDED"))
                .andExpect(jsonPath("$.path").value(REFUND_URL))
                .andExpect(jsonPath("$.traceId").value("client-trace-0001"));
    }

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void 예상하지_못한_예외는_500_공통_메시지로_응답하고_같은_traceId로_스택_트레이스를_남긴다(CapturedOutput output)
            throws Exception {
        given(adminPaymentService.refund(anyLong(), anyString()))
                .willThrow(new IllegalStateException("내부 구현 상세 메시지"));

        MvcResult result = mockMvc.perform(patch(REFUND_URL).header(HttpHeaders.AUTHORIZATION, adminBearer()))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.status").value(500))
                .andExpect(jsonPath("$.message").value(containsString("traceId")))
                .andReturn();

        String traceId = traceIdHeader(result);
        // 내부 예외 메시지는 응답에 노출하지 않음
        assertThat(result.getResponse().getContentAsString()).doesNotContain("내부 구현 상세 메시지");
        // 서버 로그에는 같은 traceId와 함께 원인(스택 트레이스)이 남음 → 응답의 traceId로 로그 검색 가능
        assertThat(output).contains("[" + traceId + "]")
                .contains("처리되지 않은 예외 PATCH " + REFUND_URL)
                .contains("java.lang.IllegalStateException: 내부 구현 상세 메시지");
        // 요청 로그도 같은 traceId로 남음 (요청 → 예외 로그 연결)
        assertThat(output).contains("HTTP PATCH " + REFUND_URL + " status=500");
    }

    @Test
    void 지원하지_않는_메서드_405도_공통_형식이고_Allow_헤더를_유지한다() throws Exception {
        mockMvc.perform(get(REFUND_URL).header(HttpHeaders.AUTHORIZATION, adminBearer()))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(header().string(HttpHeaders.ALLOW, containsString("PATCH")))
                .andExpect(jsonPath("$.status").value(405))
                .andExpect(jsonPath("$.message").value("지원하지 않는 HTTP 메서드입니다."))
                .andExpect(jsonPath("$.traceId").isNotEmpty());
    }

    @Test
    void 없는_경로_404도_공통_형식이다() throws Exception {
        mockMvc.perform(get("/api/admin/unknown").header(HttpHeaders.AUTHORIZATION, adminBearer()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.message").value("요청한 경로를 찾을 수 없습니다."))
                .andExpect(jsonPath("$.path").value("/api/admin/unknown"));
    }
}
