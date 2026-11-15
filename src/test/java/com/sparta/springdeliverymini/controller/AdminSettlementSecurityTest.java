package com.sparta.springdeliverymini.controller;

import com.sparta.springdeliverymini.config.SecurityConfig;
import com.sparta.springdeliverymini.entity.Role;
import com.sparta.springdeliverymini.entity.SettlementAggregationFailureStatus;
import com.sparta.springdeliverymini.entity.SettlementStatus;
import com.sparta.springdeliverymini.exception.InvalidSettlementStatusException;
import com.sparta.springdeliverymini.jwt.JwtProvider;
import com.sparta.springdeliverymini.service.AdminSettlementService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 관리자 정산 API의 URL 단위 권한 규칙(SecurityConfig)과 요청 변환 검증
// Controller + Security 필터만 띄우고 Service는 Mock으로 대체 → DB 없이 실행 가능
@WebMvcTest(AdminSettlementController.class)
@Import({SecurityConfig.class, JwtProvider.class})
class AdminSettlementSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtProvider jwtProvider;

    @MockitoBean
    private AdminSettlementService adminSettlementService;

    private String bearer(String username, Role role) {
        return "Bearer " + jwtProvider.createToken(username, role);
    }

    @Test
    void 토큰이_없으면_401() throws Exception {
        mockMvc.perform(patch("/api/admin/settlements/1/pay"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(patch("/api/admin/settlements/1/confirm"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/admin/settlements").param("date", "2026-11-05"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(adminSettlementService);
    }

    @Test
    void OWNER와_CUSTOMER는_403이고_Service까지_가지_않는다() throws Exception {
        for (Role role : new Role[]{Role.OWNER, Role.CUSTOMER}) {
            String token = bearer("user1", role);
            mockMvc.perform(patch("/api/admin/settlements/1/pay").header(HttpHeaders.AUTHORIZATION, token))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.message").value("권한이 없습니다."));
            mockMvc.perform(patch("/api/admin/settlements/1/confirm").header(HttpHeaders.AUTHORIZATION, token))
                    .andExpect(status().isForbidden());
            mockMvc.perform(get("/api/admin/settlements").param("date", "2026-11-05")
                            .header(HttpHeaders.AUTHORIZATION, token))
                    .andExpect(status().isForbidden());
        }
        verifyNoInteractions(adminSettlementService);
    }

    @Test
    void ADMIN은_확정할_수_있다() throws Exception {
        mockMvc.perform(patch("/api/admin/settlements/1/confirm")
                        .header(HttpHeaders.AUTHORIZATION, bearer("admin", Role.ADMIN)))
                .andExpect(status().isOk());

        verify(adminSettlementService).confirm(1L, "admin");
    }

    @Test
    void ADMIN은_지급할_수_있다() throws Exception {
        mockMvc.perform(patch("/api/admin/settlements/1/pay")
                        .header(HttpHeaders.AUTHORIZATION, bearer("admin", Role.ADMIN)))
                .andExpect(status().isOk());

        verify(adminSettlementService).pay(1L, "admin");
    }

    @Test
    void ADMIN은_날짜와_상태로_목록을_조회할_수_있다() throws Exception {
        mockMvc.perform(get("/api/admin/settlements")
                        .param("date", "2026-11-05").param("status", "PENDING")
                        .header(HttpHeaders.AUTHORIZATION, bearer("admin", Role.ADMIN)))
                .andExpect(status().isOk());

        verify(adminSettlementService).getSettlements(LocalDate.of(2026, 11, 5), SettlementStatus.PENDING, "admin");
    }

    @Test
    void 잘못된_상태_전이는_409() throws Exception {
        given(adminSettlementService.pay(anyLong(), anyString()))
                .willThrow(new InvalidSettlementStatusException("확정(CONFIRMED) 상태의 정산만 지급할 수 있습니다. 현재 상태: PAID"));

        mockMvc.perform(patch("/api/admin/settlements/1/pay")
                        .header(HttpHeaders.AUTHORIZATION, bearer("admin", Role.ADMIN)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("확정(CONFIRMED) 상태의 정산만 지급할 수 있습니다. 현재 상태: PAID"));
    }

    // ---- 재집계·감사 기록 (#39) ----

    @Test
    void 재집계와_감사_기록_조회도_토큰이_없으면_401이고_ADMIN이_아니면_403() throws Exception {
        mockMvc.perform(post("/api/admin/settlements/reaggregate").param("date", "2026-11-05"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/admin/settlements/1/histories"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/admin/settlements/aggregation-failures"))
                .andExpect(status().isUnauthorized());

        for (Role role : new Role[]{Role.OWNER, Role.CUSTOMER}) {
            String token = bearer("user1", role);
            mockMvc.perform(post("/api/admin/settlements/reaggregate").param("date", "2026-11-05")
                            .header(HttpHeaders.AUTHORIZATION, token))
                    .andExpect(status().isForbidden());
            mockMvc.perform(get("/api/admin/settlements/1/histories").header(HttpHeaders.AUTHORIZATION, token))
                    .andExpect(status().isForbidden());
            mockMvc.perform(get("/api/admin/settlements/aggregation-failures").header(HttpHeaders.AUTHORIZATION, token))
                    .andExpect(status().isForbidden());
        }
        verifyNoInteractions(adminSettlementService);
    }

    @Test
    void ADMIN은_날짜와_매장으로_재집계할_수_있고_처리자는_토큰에서_가져온다() throws Exception {
        // 요청에 다른 처리자를 넣어 보내도 무시됨 — Service에는 토큰의 username만 전달
        mockMvc.perform(post("/api/admin/settlements/reaggregate")
                        .param("date", "2026-11-05").param("storeId", "7")
                        .param("username", "someone-else").param("actorId", "999")
                        .header(HttpHeaders.AUTHORIZATION, bearer("admin", Role.ADMIN)))
                .andExpect(status().isOk());

        verify(adminSettlementService).reaggregate(LocalDate.of(2026, 11, 5), 7L, "admin");
    }

    @Test
    void 매장을_지정하지_않으면_날짜_전체를_재집계한다() throws Exception {
        mockMvc.perform(post("/api/admin/settlements/reaggregate").param("date", "2026-11-05")
                        .header(HttpHeaders.AUTHORIZATION, bearer("admin", Role.ADMIN)))
                .andExpect(status().isOk());

        verify(adminSettlementService).reaggregate(LocalDate.of(2026, 11, 5), null, "admin");
    }

    @Test
    void 이미_정산이_있는_매장의_재집계는_409() throws Exception {
        given(adminSettlementService.reaggregate(any(), anyLong(), anyString()))
                .willThrow(new InvalidSettlementStatusException("이미 정산이 있는 매장·날짜는 재집계할 수 없습니다. 현재 상태: PAID"));

        mockMvc.perform(post("/api/admin/settlements/reaggregate")
                        .param("date", "2026-11-05").param("storeId", "7")
                        .header(HttpHeaders.AUTHORIZATION, bearer("admin", Role.ADMIN)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("이미 정산이 있는 매장·날짜는 재집계할 수 없습니다. 현재 상태: PAID"));
    }

    @Test
    void 재집계의_날짜_누락_형식_오류는_400() throws Exception {
        String token = bearer("admin", Role.ADMIN);
        mockMvc.perform(post("/api/admin/settlements/reaggregate").header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/admin/settlements/reaggregate").param("date", "2026-11-05").param("storeId", "abc")
                        .header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(adminSettlementService);
    }

    @Test
    void ADMIN은_정산_이력과_집계_실패_기록을_조회할_수_있다() throws Exception {
        String token = bearer("admin", Role.ADMIN);
        mockMvc.perform(get("/api/admin/settlements/1/histories").header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/admin/settlements/aggregation-failures").param("status", "FAILED")
                        .header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/admin/settlements/aggregation-failures").param("status", "DONE")
                        .header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(status().isBadRequest());

        verify(adminSettlementService).getHistories(1L, "admin");
        verify(adminSettlementService).getAggregationFailures(SettlementAggregationFailureStatus.FAILED, "admin");
    }

    @Test
    void 목록_조회의_날짜_누락_형식_오류_없는_상태는_400() throws Exception {
        String token = bearer("admin", Role.ADMIN);
        mockMvc.perform(get("/api/admin/settlements").header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/admin/settlements").param("date", "2026-13-01")
                        .header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/admin/settlements").param("date", "2026-11-05").param("status", "DONE")
                        .header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(adminSettlementService);
    }
}
