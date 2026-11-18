package com.sparta.springdeliverymini.controller;

import com.sparta.springdeliverymini.config.SecurityConfig;
import com.sparta.springdeliverymini.dto.MySettlementResponse;
import com.sparta.springdeliverymini.dto.PageQuery;
import com.sparta.springdeliverymini.entity.Role;
import com.sparta.springdeliverymini.entity.SettlementStatus;
import com.sparta.springdeliverymini.exception.ApiException;
import com.sparta.springdeliverymini.jwt.JwtProvider;
import com.sparta.springdeliverymini.service.OwnerSettlementService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.SliceImpl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 내 정산 내역 API(GET /api/settlements)의 URL 권한 규칙·파라미터 바인딩·페이지 헤더 검증
// Controller + Security 필터만 띄우고 Service는 Mock으로 대체 → DB 없이 실행 가능
@WebMvcTest(SettlementController.class)
@Import({SecurityConfig.class, JwtProvider.class})
class SettlementQuerySecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtProvider jwtProvider;

    @MockitoBean
    private OwnerSettlementService ownerSettlementService;

    private String bearer(String username, Role role) {
        return "Bearer " + jwtProvider.createToken(username, role);
    }

    private static MySettlementResponse settlement(long id) {
        return new MySettlementResponse(id, 1L, "치킨집", LocalDate.of(2026, 11, 5),
                2, 20000, 1000, 19000, 0, 0, 0, 0, 0, 0, 19000,
                SettlementStatus.PAID, LocalDateTime.of(2026, 11, 6, 1, 10),
                LocalDateTime.of(2026, 11, 6, 9, 0), LocalDateTime.of(2026, 11, 6, 10, 0));
    }

    @Test
    void OWNER는_조건과_페이지를_넘겨_목록을_받고_페이지_정보는_헤더로_받는다() throws Exception {
        PageQuery pageQuery = PageQuery.of(1, 1);
        given(ownerSettlementService.getMySettlements("owner1", 1L, SettlementStatus.PAID,
                LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 30), pageQuery))
                .willReturn(new SliceImpl<>(List.of(settlement(3)), pageQuery.toPageable(), true));

        mockMvc.perform(get("/api/settlements")
                        .param("storeId", "1").param("status", "PAID")
                        .param("from", "2026-11-01").param("to", "2026-11-30")
                        .param("page", "1").param("size", "1")
                        .header(HttpHeaders.AUTHORIZATION, bearer("owner1", Role.OWNER)))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Page", "1"))
                .andExpect(header().string("X-Size", "1"))
                .andExpect(header().string("X-Has-Next", "true"))
                .andExpect(jsonPath("$[0].id").value(3))
                .andExpect(jsonPath("$[0].storeId").value(1))
                .andExpect(jsonPath("$[0].storeName").value("치킨집"))
                .andExpect(jsonPath("$[0].settlementDate").value("2026-11-05"))
                .andExpect(jsonPath("$[0].settlementAmount").value(19000))
                .andExpect(jsonPath("$[0].status").value("PAID"))
                // 관리자 응답의 처리자 정보는 사장님 응답에 없음
                .andExpect(jsonPath("$[0].confirmedBy").doesNotExist())
                .andExpect(jsonPath("$[0].paidBy").doesNotExist());
    }

    @Test
    void 조건이_없으면_전체_매장_첫_페이지를_조회한다() throws Exception {
        PageQuery first = PageQuery.of(0, PageQuery.DEFAULT_SIZE);
        given(ownerSettlementService.getMySettlements("owner1", null, null, null, null, first))
                .willReturn(new SliceImpl<>(List.of(), first.toPageable(), false));

        mockMvc.perform(get("/api/settlements")
                        .header(HttpHeaders.AUTHORIZATION, bearer("owner1", Role.OWNER)))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Has-Next", "false"))
                .andExpect(jsonPath("$").isEmpty());

        verify(ownerSettlementService).getMySettlements(eq("owner1"), isNull(), isNull(), isNull(), isNull(), eq(first));
    }

    @Test
    void CUSTOMER와_ADMIN은_Security에서_403으로_막힌다() throws Exception {
        mockMvc.perform(get("/api/settlements")
                        .header(HttpHeaders.AUTHORIZATION, bearer("customer1", Role.CUSTOMER)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("권한이 없습니다."));
        mockMvc.perform(get("/api/settlements")
                        .header(HttpHeaders.AUTHORIZATION, bearer("admin", Role.ADMIN)))
                .andExpect(status().isForbidden());

        verifyNoInteractions(ownerSettlementService);
    }

    @Test
    void 토큰이_없으면_401() throws Exception {
        mockMvc.perform(get("/api/settlements"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("인증이 필요합니다."));

        verifyNoInteractions(ownerSettlementService);
    }

    @Test
    void 잘못된_status_날짜_형식_페이지_크기는_400() throws Exception {
        String owner = bearer("owner1", Role.OWNER);
        mockMvc.perform(get("/api/settlements").param("status", "DONE").header(HttpHeaders.AUTHORIZATION, owner))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/settlements").param("from", "2026/11/01").header(HttpHeaders.AUTHORIZATION, owner))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/settlements").param("size", "101").header(HttpHeaders.AUTHORIZATION, owner))
                .andExpect(status().isBadRequest());

        verify(ownerSettlementService, never()).getMySettlements(anyString(), any(), any(), any(), any(), any());
    }

    @Test
    void 남의_매장을_지정하면_서비스의_403을_공통_에러_형식으로_응답한다() throws Exception {
        given(ownerSettlementService.getMySettlements(eq("owner1"), eq(2L), any(), any(), any(), any()))
                .willThrow(new ApiException(HttpStatus.FORBIDDEN, "본인 매장이 아닙니다."));

        mockMvc.perform(get("/api/settlements").param("storeId", "2")
                        .header(HttpHeaders.AUTHORIZATION, bearer("owner1", Role.OWNER)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("본인 매장이 아닙니다."));
    }
}
