package com.sparta.springdeliverymini.controller;

import com.sparta.springdeliverymini.config.SecurityConfig;
import com.sparta.springdeliverymini.entity.Role;
import com.sparta.springdeliverymini.jwt.JwtProvider;
import com.sparta.springdeliverymini.service.OrderService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.mapping.JpaMetamodelMappingContext;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 주문 취소 API의 URL 단위 권한 규칙(SecurityConfig) 검증
// Controller + Security 필터만 띄우고 Service는 Mock으로 대체 → DB 없이 실행 가능
@WebMvcTest(OrderController.class)
@Import({SecurityConfig.class, JwtProvider.class})
class OrderCancelSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtProvider jwtProvider;

    @MockitoBean
    private OrderService orderService;

    // 메인 클래스의 @EnableJpaAuditing이 JPA 메타모델을 요구하는데,
    // @WebMvcTest는 JPA를 띄우지 않으므로 Mock으로 대체
    @MockitoBean
    private JpaMetamodelMappingContext jpaMetamodelMappingContext;

    @Test
    void CUSTOMER는_주문을_취소할_수_있다() throws Exception {
        String token = jwtProvider.createToken("customer1", Role.CUSTOMER);

        mockMvc.perform(patch("/api/orders/1/cancel")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isNoContent());

        verify(orderService).cancelOrder(1L, "customer1");
    }

    @Test
    void OWNER는_Security에서_403으로_막힌다() throws Exception {
        String token = jwtProvider.createToken("owner1", Role.OWNER);

        mockMvc.perform(patch("/api/orders/1/cancel")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("권한이 없습니다."));

        // Service까지 도달하지 않아야 함
        verify(orderService, never()).cancelOrder(anyLong(), any());
    }

    @Test
    void 토큰이_없으면_401() throws Exception {
        mockMvc.perform(patch("/api/orders/1/cancel"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("인증이 필요합니다."));

        verify(orderService, never()).cancelOrder(anyLong(), any());
    }
}
