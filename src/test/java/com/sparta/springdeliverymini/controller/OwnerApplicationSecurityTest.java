package com.sparta.springdeliverymini.controller;

import com.sparta.springdeliverymini.config.SecurityConfig;
import com.sparta.springdeliverymini.entity.Role;
import com.sparta.springdeliverymini.jwt.JwtProvider;
import com.sparta.springdeliverymini.service.OwnerApplicationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 사장님 전환 신청·승인 API의 URL 단위 권한 규칙과 요청 검증 (Service는 Mock, DB 없이)
@WebMvcTest({OwnerApplicationController.class, AdminOwnerApplicationController.class})
@Import({SecurityConfig.class, JwtProvider.class})
class OwnerApplicationSecurityTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private JwtProvider jwtProvider;

    @MockitoBean private OwnerApplicationService ownerApplicationService;

    private String bearer(Role role) {
        return "Bearer " + jwtProvider.createToken("user1", role);
    }

    @Test
    void 토큰이_없으면_신청_조회_관리자_API_모두_401() throws Exception {
        mockMvc.perform(post("/api/owner-applications").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"businessName\":\"치킨집\"}"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/owner-applications/me")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/admin/owner-applications")).andExpect(status().isUnauthorized());
        mockMvc.perform(patch("/api/admin/owner-applications/1/approve")).andExpect(status().isUnauthorized());
        verifyNoInteractions(ownerApplicationService);
    }

    @Test
    void 신청은_CUSTOMER만_가능하고_OWNER와_ADMIN은_403() throws Exception {
        for (Role role : new Role[]{Role.OWNER, Role.ADMIN}) {
            mockMvc.perform(post("/api/owner-applications")
                            .header(HttpHeaders.AUTHORIZATION, bearer(role))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"businessName\":\"치킨집\"}"))
                    .andExpect(status().isForbidden());
        }
        verifyNoInteractions(ownerApplicationService);
    }

    @Test
    void 고객의_신청은_201이고_상호가_비었으면_400() throws Exception {
        mockMvc.perform(post("/api/owner-applications")
                        .header(HttpHeaders.AUTHORIZATION, bearer(Role.CUSTOMER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"businessName\":\"치킨집\"}"))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/owner-applications")
                        .header(HttpHeaders.AUTHORIZATION, bearer(Role.CUSTOMER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"businessName\":\" \"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void 관리자_API는_CUSTOMER와_OWNER에게_403() throws Exception {
        for (Role role : new Role[]{Role.CUSTOMER, Role.OWNER}) {
            mockMvc.perform(get("/api/admin/owner-applications").header(HttpHeaders.AUTHORIZATION, bearer(role)))
                    .andExpect(status().isForbidden());
            mockMvc.perform(patch("/api/admin/owner-applications/1/approve").header(HttpHeaders.AUTHORIZATION, bearer(role)))
                    .andExpect(status().isForbidden());
            mockMvc.perform(patch("/api/admin/owner-applications/1/reject")
                            .header(HttpHeaders.AUTHORIZATION, bearer(role))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"reason\":\"사유\"}"))
                    .andExpect(status().isForbidden());
        }
        verifyNoInteractions(ownerApplicationService);
    }

    @Test
    void 거절_사유가_없거나_상태_값이_잘못되면_400() throws Exception {
        mockMvc.perform(patch("/api/admin/owner-applications/1/reject")
                        .header(HttpHeaders.AUTHORIZATION, bearer(Role.ADMIN))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"\"}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/admin/owner-applications?status=UNKNOWN")
                        .header(HttpHeaders.AUTHORIZATION, bearer(Role.ADMIN)))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(ownerApplicationService);
    }
}
