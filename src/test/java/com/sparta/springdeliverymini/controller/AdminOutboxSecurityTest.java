package com.sparta.springdeliverymini.controller;

import com.sparta.springdeliverymini.config.SecurityConfig;
import com.sparta.springdeliverymini.dto.NotificationResponse;
import com.sparta.springdeliverymini.dto.OutboxRepublishResponse;
import com.sparta.springdeliverymini.dto.PageQuery;
import com.sparta.springdeliverymini.entity.NotificationSendStatus;
import com.sparta.springdeliverymini.entity.OutboxEventStatus;
import com.sparta.springdeliverymini.entity.OutboxEventType;
import com.sparta.springdeliverymini.entity.Role;
import com.sparta.springdeliverymini.exception.InvalidOutboxEventStatusException;
import com.sparta.springdeliverymini.jwt.JwtProvider;
import com.sparta.springdeliverymini.service.AdminOutboxService;
import com.sparta.springdeliverymini.service.NotificationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.SliceImpl;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 관리자 Outbox API(/api/admin/outbox-events)와 내 알림 API(/api/notifications)의 URL 단위 권한 규칙·요청 변환 검증
// Controller + Security 필터만 띄우고 Service는 Mock으로 대체 → DB 없이 실행 가능
@WebMvcTest({AdminOutboxController.class, NotificationController.class})
@Import({SecurityConfig.class, JwtProvider.class})
class AdminOutboxSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtProvider jwtProvider;

    @MockitoBean
    private AdminOutboxService adminOutboxService;

    @MockitoBean
    private NotificationService notificationService;

    private String bearer(String username, Role role) {
        return "Bearer " + jwtProvider.createToken(username, role);
    }

    @Test
    void 토큰이_없으면_401() throws Exception {
        mockMvc.perform(get("/api/admin/outbox-events")).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/admin/outbox-events/1/republish")).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/admin/outbox-events/republish")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/notifications")).andExpect(status().isUnauthorized());
        verifyNoInteractions(adminOutboxService, notificationService);
    }

    @Test
    void OWNER와_CUSTOMER는_Outbox_API가_403이고_Service까지_가지_않는다() throws Exception {
        for (Role role : new Role[]{Role.OWNER, Role.CUSTOMER}) {
            mockMvc.perform(get("/api/admin/outbox-events")
                            .header(HttpHeaders.AUTHORIZATION, bearer("user1", role)))
                    .andExpect(status().isForbidden());
            mockMvc.perform(post("/api/admin/outbox-events/1/republish")
                            .header(HttpHeaders.AUTHORIZATION, bearer("user1", role)))
                    .andExpect(status().isForbidden());
        }
        verifyNoInteractions(adminOutboxService);
    }

    @Test
    void ADMIN은_기본으로_FAILED_이벤트_목록을_조회한다() throws Exception {
        given(adminOutboxService.getEvents(OutboxEventStatus.FAILED, PageQuery.of(0, 20), "admin"))
                .willReturn(new SliceImpl<>(List.of()));

        mockMvc.perform(get("/api/admin/outbox-events")
                        .header(HttpHeaders.AUTHORIZATION, bearer("admin", Role.ADMIN)))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Has-Next", "false"));

        verify(adminOutboxService).getEvents(OutboxEventStatus.FAILED, PageQuery.of(0, 20), "admin");
    }

    @Test
    void ADMIN은_단건과_일괄로_재발행할_수_있다() throws Exception {
        given(adminOutboxService.republishFailed(100, "admin")).willReturn(new OutboxRepublishResponse(3));

        mockMvc.perform(post("/api/admin/outbox-events/1/republish")
                        .header(HttpHeaders.AUTHORIZATION, bearer("admin", Role.ADMIN)))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/admin/outbox-events/republish")
                        .header(HttpHeaders.AUTHORIZATION, bearer("admin", Role.ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.republished").value(3));

        verify(adminOutboxService).republish(1L, "admin");
        verify(adminOutboxService).republishFailed(100, "admin"); // limit 기본값
    }

    @Test
    void FAILED가_아닌_이벤트_재발행은_409() throws Exception {
        given(adminOutboxService.republish(anyLong(), anyString()))
                .willThrow(new InvalidOutboxEventStatusException("발행 실패(FAILED) 상태의 이벤트만 재발행할 수 있습니다. 현재 상태: PUBLISHED"));

        mockMvc.perform(post("/api/admin/outbox-events/1/republish")
                        .header(HttpHeaders.AUTHORIZATION, bearer("admin", Role.ADMIN)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("발행 실패(FAILED) 상태의 이벤트만 재발행할 수 있습니다. 현재 상태: PUBLISHED"));
    }

    @Test
    void 내_알림_목록은_로그인한_모든_역할이_조회할_수_있다() throws Exception {
        NotificationResponse response = new NotificationResponse(1L, OutboxEventType.PAYMENT_COMPLETED, 10L,
                "주문 #10 결제가 완료되었습니다. (10,000원)", LocalDateTime.of(2026, 11, 14, 12, 0),
                NotificationSendStatus.SENT, LocalDateTime.of(2026, 11, 14, 12, 0, 5));
        given(notificationService.getMyNotifications("user1", PageQuery.of(0, 20)))
                .willReturn(new SliceImpl<>(List.of(response)));

        for (Role role : Role.values()) {
            mockMvc.perform(get("/api/notifications")
                            .header(HttpHeaders.AUTHORIZATION, bearer("user1", role)))
                    .andExpect(status().isOk())
                    .andExpect(header().string("X-Has-Next", "false"))
                    .andExpect(jsonPath("$[0].orderId").value(10))
                    .andExpect(jsonPath("$[0].sendStatus").value("SENT"));
        }
    }

    @Test
    void 페이지_크기가_범위를_벗어나면_400() throws Exception {
        mockMvc.perform(get("/api/notifications?size=101")
                        .header(HttpHeaders.AUTHORIZATION, bearer("user1", Role.CUSTOMER)))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(notificationService);
    }
}
