package com.sparta.springdeliverymini.controller;

import com.sparta.springdeliverymini.config.SecurityConfig;
import com.sparta.springdeliverymini.dto.OrderResponse;
import com.sparta.springdeliverymini.dto.PageQuery;
import com.sparta.springdeliverymini.entity.OrderStatus;
import com.sparta.springdeliverymini.entity.Role;
import com.sparta.springdeliverymini.jwt.JwtProvider;
import com.sparta.springdeliverymini.service.OrderService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.SliceImpl;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// CORS (#61) — 허용 출처의 Preflight·실제 요청, 허용하지 않은 출처 차단, 노출·허용 헤더, 자격 증명 허용(V15 Refresh Token 쿠키)
// 허용 출처는 설정값으로만 주입 (test 프로필 기본값은 빈 목록이라 여기서 지정)
@WebMvcTest(value = OrderController.class, properties = "cors.allowed-origins=http://localhost:5173")
@Import({SecurityConfig.class, JwtProvider.class})
class CorsSecurityTest {

    private static final String ALLOWED = "http://localhost:5173";
    private static final String OTHER = "https://evil.example.com";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtProvider jwtProvider;

    @MockitoBean
    private OrderService orderService;

    private String customerToken() {
        return "Bearer " + jwtProvider.createToken("customer1", Role.CUSTOMER);
    }

    @Test
    void 허용_출처의_Preflight는_토큰_없이_통과하고_허용_메서드와_헤더를_알려준다() throws Exception {
        mockMvc.perform(options("/api/payments")
                        .header(HttpHeaders.ORIGIN, ALLOWED)
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "authorization, content-type, idempotency-key, x-trace-id"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, ALLOWED))
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_METHODS, containsString("POST")))
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_HEADERS, containsString("idempotency-key")))
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_HEADERS, containsString("authorization")))
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_MAX_AGE, "3600"))
                // Refresh Token 쿠키(HttpOnly) 때문에 자격 증명 허용 (V15, #61에서는 끔) — 허용 출처에만 붙음
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS, "true"));
    }

    @Test
    void 관리자_API의_Preflight도_인증_없이_통과한다() throws Exception {
        // Preflight는 브라우저가 토큰을 붙이지 않고 보냄 → 인증이 필요한 경로에서 401이 나면 실제 요청을 보낼 수 없음
        mockMvc.perform(options("/api/admin/outbox-events")
                        .header(HttpHeaders.ORIGIN, ALLOWED)
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "authorization"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, ALLOWED));
    }

    @Test
    void 허용_출처의_실제_요청은_페이징_헤더를_브라우저에_노출한다() throws Exception {
        PageQuery firstPage = PageQuery.of(0, 20);
        given(orderService.getOrders("customer1", firstPage)).willReturn(
                new SliceImpl<>(List.of(new OrderResponse(7L, 1L, "치킨집", List.of(), 10000L, "서울시", null, null, null, null,
                        OrderStatus.ORDER_REQUEST, null, null, null)), firstPage.toPageable(), true));

        mockMvc.perform(get("/api/orders")
                        .header(HttpHeaders.ORIGIN, ALLOWED)
                        .header(HttpHeaders.AUTHORIZATION, customerToken()))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, ALLOWED))
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_EXPOSE_HEADERS, containsString("X-Has-Next")))
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_EXPOSE_HEADERS, containsString("X-Next-After-Id")))
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS, "true"))
                .andExpect(header().string("X-Has-Next", "true"));
    }

    @Test
    void 허용하지_않은_출처의_Preflight는_403이고_허용_헤더를_주지_않는다() throws Exception {
        mockMvc.perform(options("/api/orders")
                        .header(HttpHeaders.ORIGIN, OTHER)
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET"))
                .andExpect(status().isForbidden())
                .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));
    }

    @Test
    void 허용하지_않은_출처의_실제_요청은_토큰이_있어도_컨트롤러에_닿지_않는다() throws Exception {
        mockMvc.perform(get("/api/orders")
                        .header(HttpHeaders.ORIGIN, OTHER)
                        .header(HttpHeaders.AUTHORIZATION, customerToken()))
                .andExpect(status().isForbidden())
                .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));

        verifyNoInteractions(orderService);
    }

    @Test
    void 허용하지_않은_요청_헤더가_있으면_Preflight를_거부한다() throws Exception {
        mockMvc.perform(options("/api/orders")
                        .header(HttpHeaders.ORIGIN, ALLOWED)
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "x-custom-header"))
                .andExpect(status().isForbidden());
    }

    @Test
    void Origin이_없는_요청은_CORS와_관계없이_기존_인증_규칙대로_처리한다() throws Exception {
        // curl·서버 간 호출 등 브라우저 밖 요청은 CORS 대상이 아님 → 토큰 없으면 기존처럼 401
        mockMvc.perform(get("/api/orders"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));

        verifyNoInteractions(orderService);
    }
}
