package com.sparta.springdeliverymini.controller;

import com.sparta.springdeliverymini.config.SecurityConfig;
import com.sparta.springdeliverymini.dto.AdminStoreFeeRateResponse;
import com.sparta.springdeliverymini.entity.Role;
import com.sparta.springdeliverymini.entity.Store;
import com.sparta.springdeliverymini.entity.User;
import com.sparta.springdeliverymini.jwt.JwtProvider;
import com.sparta.springdeliverymini.service.AdminStoreService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 관리자 매장 수수료율 API의 URL 단위 권한 규칙(SecurityConfig의 /api/admin/**)과 요청 검증(400),
// 응답 JSON에서 미설정(null)과 0%(0.0000)가 구분되는지 (앱의 실제 Jackson 설정으로 직렬화)
// Controller + Security 필터만 띄우고 Service는 Mock으로 대체 → DB 없이 실행 가능
@WebMvcTest(AdminStoreController.class)
@Import({SecurityConfig.class, JwtProvider.class})
class AdminStoreSecurityTest {

    private static final String URL = "/api/admin/stores/1/fee-rate";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtProvider jwtProvider;

    @MockitoBean
    private AdminStoreService adminStoreService;

    private String bearer(String username, Role role) {
        return "Bearer " + jwtProvider.createToken(username, role);
    }

    private org.springframework.test.web.servlet.ResultActions putFeeRate(String body, Role role) throws Exception {
        return mockMvc.perform(put(URL)
                .header(HttpHeaders.AUTHORIZATION, bearer("admin", role))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    @Test
    void 토큰이_없으면_401() throws Exception {
        mockMvc.perform(put(URL).contentType(MediaType.APPLICATION_JSON).content("{\"feeRate\": 0.03}"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(delete(URL))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(adminStoreService);
    }

    @Test
    void OWNER와_CUSTOMER는_403이고_Service까지_가지_않는다() throws Exception {
        for (Role role : new Role[]{Role.OWNER, Role.CUSTOMER}) {
            putFeeRate("{\"feeRate\": 0.03}", role)
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.message").value("권한이 없습니다."));
            mockMvc.perform(delete(URL).header(HttpHeaders.AUTHORIZATION, bearer("user1", role)))
                    .andExpect(status().isForbidden());
        }
        verifyNoInteractions(adminStoreService);
    }

    @Test
    void ADMIN은_수수료율을_설정할_수_있다() throws Exception {
        putFeeRate("{\"feeRate\": 0.03}", Role.ADMIN)
                .andExpect(status().isOk());

        verify(adminStoreService).changeFeeRate(1L, new BigDecimal("0.03"), "admin");
    }

    @Test
    void 경계값_0과_1은_허용한다() throws Exception {
        putFeeRate("{\"feeRate\": 0}", Role.ADMIN).andExpect(status().isOk());
        putFeeRate("{\"feeRate\": 1.0000}", Role.ADMIN).andExpect(status().isOk());
    }

    @Test
    void ADMIN은_수수료율을_해제할_수_있다() throws Exception {
        mockMvc.perform(delete(URL).header(HttpHeaders.AUTHORIZATION, bearer("admin", Role.ADMIN)))
                .andExpect(status().isOk());

        verify(adminStoreService).clearFeeRate(1L, "admin");
    }

    @Test
    void 잘못된_수수료율은_400이고_Service까지_가지_않는다() throws Exception {
        String[] invalidBodies = {
                "{}",                        // 누락
                "{\"feeRate\": null}",       // null
                "{\"feeRate\": -0.0001}",    // 0 미만
                "{\"feeRate\": 1.0001}",     // 1 초과
                "{\"feeRate\": 1.5}",
                "{\"feeRate\": 0.00001}",    // 소수점 5자리
                "{\"feeRate\": \"abc\"}"     // 숫자가 아님
        };
        for (String body : invalidBodies) {
            putFeeRate(body, Role.ADMIN)
                    .andExpect(status().isBadRequest());
        }
        verify(adminStoreService, never()).changeFeeRate(anyLong(), any(), anyString());
    }

    // ===== 응답 JSON — 미설정(null)과 0%(0.0000) 구분 =====

    private static final BigDecimal DEFAULT_RATE = new BigDecimal("0.05");

    // Service는 Mock이지만 응답은 실제 Store 엔티티로 만듦 → 엔티티의 자릿수 처리부터 JSON 직렬화까지 그대로 검증
    private static AdminStoreFeeRateResponse responseOf(BigDecimal feeRate) {
        Store store = new Store("치킨집", new User("owner1", "pw", Role.OWNER));
        org.springframework.test.util.ReflectionTestUtils.setField(store, "id", 1L);
        if (feeRate != null) {
            store.changeFeeRate(feeRate);
        }
        return AdminStoreFeeRateResponse.of(store, DEFAULT_RATE);
    }

    @Test
    void 수수료율_0은_feeRate가_0으로_내려가고_공통_수수료율_사용이_아니다() throws Exception {
        given(adminStoreService.changeFeeRate(eq(1L), any(), eq("admin")))
                .willReturn(responseOf(BigDecimal.ZERO));

        putFeeRate("{\"feeRate\": 0}", Role.ADMIN)
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("\"feeRate\":0.0000")))
                .andExpect(content().string(containsString("\"effectiveFeeRate\":0.0000")))
                .andExpect(jsonPath("$.defaultFeeRateApplied").value(false));
    }

    @Test
    void 해제하면_feeRate_키가_null로_내려가고_공통_수수료율이_적용된다() throws Exception {
        given(adminStoreService.clearFeeRate(1L, "admin")).willReturn(responseOf(null));

        mockMvc.perform(delete(URL).header(HttpHeaders.AUTHORIZATION, bearer("admin", Role.ADMIN)))
                .andExpect(status().isOk())
                // 키가 생략되지 않고 명시적으로 null (클라이언트가 "필드 없음"과 혼동하지 않도록)
                .andExpect(content().string(containsString("\"feeRate\":null")))
                .andExpect(content().string(containsString("\"effectiveFeeRate\":0.05")))
                .andExpect(jsonPath("$.defaultFeeRateApplied").value(true));
    }
}
