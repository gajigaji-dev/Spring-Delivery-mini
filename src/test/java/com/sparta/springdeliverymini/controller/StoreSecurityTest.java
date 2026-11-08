package com.sparta.springdeliverymini.controller;

import com.sparta.springdeliverymini.config.SecurityConfig;
import com.sparta.springdeliverymini.entity.Role;
import com.sparta.springdeliverymini.jwt.JwtProvider;
import com.sparta.springdeliverymini.service.OrderService;
import com.sparta.springdeliverymini.service.SettlementService;
import com.sparta.springdeliverymini.service.StoreService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 매장 API의 URL 단위 권한 규칙(SecurityConfig) 검증
// Controller + Security 필터만 띄우고 Service는 Mock으로 대체 → DB 없이 실행 가능
@WebMvcTest(StoreController.class)
@Import({SecurityConfig.class, JwtProvider.class})
class StoreSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtProvider jwtProvider;

    @MockitoBean
    private StoreService storeService;

    @MockitoBean
    private OrderService orderService;

    @MockitoBean
    private SettlementService settlementService;

    private String bearer(String username, Role role) {
        return "Bearer " + jwtProvider.createToken(username, role);
    }

    @Test
    void OWNER는_매장을_생성할_수_있다() throws Exception {
        mockMvc.perform(post("/api/stores")
                        .header(HttpHeaders.AUTHORIZATION, bearer("owner1", Role.OWNER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{ \"name\": \"치킨집\" }"))
                .andExpect(status().isCreated());

        verify(storeService).createStore(any(), any());
    }

    @Test
    void CUSTOMER는_매장_생성이_Security에서_403() throws Exception {
        mockMvc.perform(post("/api/stores")
                        .header(HttpHeaders.AUTHORIZATION, bearer("customer1", Role.CUSTOMER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{ \"name\": \"치킨집\" }"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("권한이 없습니다."));

        verify(storeService, never()).createStore(any(), any());
    }

    @Test
    void 매장_이름이_비어있으면_400() throws Exception {
        mockMvc.perform(post("/api/stores")
                        .header(HttpHeaders.AUTHORIZATION, bearer("owner1", Role.OWNER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{ \"name\": \" \" }"))
                .andExpect(status().isBadRequest());

        verify(storeService, never()).createStore(any(), any());
    }

    @Test
    void 매장_조회는_토큰_없이_가능하다() throws Exception {
        mockMvc.perform(get("/api/stores")).andExpect(status().isOk());
        mockMvc.perform(get("/api/stores/1")).andExpect(status().isOk());
    }

    @Test
    void OWNER는_매장_주문을_조회할_수_있다() throws Exception {
        mockMvc.perform(get("/api/stores/1/orders")
                        .header(HttpHeaders.AUTHORIZATION, bearer("owner1", Role.OWNER)))
                .andExpect(status().isOk());

        verify(orderService).getStoreOrders(1L, "owner1");
    }

    @Test
    void CUSTOMER는_매장_주문_조회가_Security에서_403() throws Exception {
        mockMvc.perform(get("/api/stores/1/orders")
                        .header(HttpHeaders.AUTHORIZATION, bearer("customer1", Role.CUSTOMER)))
                .andExpect(status().isForbidden());

        verify(orderService, never()).getStoreOrders(anyLong(), any());
    }

    @Test
    void 토큰_없이_매장_주문을_조회하면_401() throws Exception {
        // 매장 조회(permitAll)와 달리 매장 주문 조회는 인증 필요
        mockMvc.perform(get("/api/stores/1/orders"))
                .andExpect(status().isUnauthorized());

        verify(orderService, never()).getStoreOrders(anyLong(), any());
    }

    // ===== 정산 조회 =====

    @Test
    void OWNER는_매장_정산을_조회할_수_있다() throws Exception {
        mockMvc.perform(get("/api/stores/1/settlements")
                        .param("from", "2026-11-01").param("to", "2026-11-05")
                        .header(HttpHeaders.AUTHORIZATION, bearer("owner1", Role.OWNER)))
                .andExpect(status().isOk());

        verify(settlementService).getStoreSettlement(
                1L, LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 5), "owner1");
    }

    @Test
    void CUSTOMER는_매장_정산_조회가_Security에서_403() throws Exception {
        mockMvc.perform(get("/api/stores/1/settlements")
                        .param("from", "2026-11-01").param("to", "2026-11-05")
                        .header(HttpHeaders.AUTHORIZATION, bearer("customer1", Role.CUSTOMER)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("권한이 없습니다."));

        verify(settlementService, never()).getStoreSettlement(anyLong(), any(), any(), any());
    }

    @Test
    void 토큰_없이_매장_정산을_조회하면_401() throws Exception {
        mockMvc.perform(get("/api/stores/1/settlements")
                        .param("from", "2026-11-01").param("to", "2026-11-05"))
                .andExpect(status().isUnauthorized());

        verify(settlementService, never()).getStoreSettlement(anyLong(), any(), any(), any());
    }

    @Test
    void 정산_조회_기간이_없으면_400() throws Exception {
        mockMvc.perform(get("/api/stores/1/settlements")
                        .param("from", "2026-11-01")
                        .header(HttpHeaders.AUTHORIZATION, bearer("owner1", Role.OWNER)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("to: 필수 값입니다."));

        verify(settlementService, never()).getStoreSettlement(anyLong(), any(), any(), any());
    }

    @Test
    void 정산_조회_날짜_형식이_틀리면_400() throws Exception {
        mockMvc.perform(get("/api/stores/1/settlements")
                        .param("from", "2026/11/01").param("to", "2026-11-05")
                        .header(HttpHeaders.AUTHORIZATION, bearer("owner1", Role.OWNER)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("from: 형식이 올바르지 않습니다."));

        verify(settlementService, never()).getStoreSettlement(anyLong(), any(), any(), any());
    }
}
