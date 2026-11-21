package com.sparta.springdeliverymini.controller;

import com.sparta.springdeliverymini.config.SecurityConfig;
import com.sparta.springdeliverymini.dto.KeysetQuery;
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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 주문 목록의 OFFSET(?page=)·keyset(?afterId=) 요청 형식과 응답 헤더 검증 (#54)
// 본문은 두 방식 모두 기존과 같은 JSON 배열 — 페이지 정보는 헤더로만 전달
@WebMvcTest(OrderController.class)
@Import({SecurityConfig.class, JwtProvider.class})
class OrderListPagingTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtProvider jwtProvider;

    @MockitoBean
    private OrderService orderService;

    private String ownerToken() {
        return "Bearer " + jwtProvider.createToken("owner1", Role.OWNER);
    }

    private static OrderResponse order(long id) {
        return new OrderResponse(id, 1L, "치킨집", List.of(), 10000L, "서울시", null, null, null, null,
                OrderStatus.ORDER_REQUEST, null, null, null);
    }

    @Test
    void 파라미터가_없으면_기존처럼_첫_페이지를_배열로_응답하고_다음_위치를_헤더로_알려준다() throws Exception {
        PageQuery firstPage = PageQuery.of(0, 20);
        given(orderService.getOrders("owner1", firstPage)).willReturn(
                new SliceImpl<>(List.of(order(3L), order(7L)), firstPage.toPageable(), true));

        mockMvc.perform(get("/api/orders").header(HttpHeaders.AUTHORIZATION, ownerToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$[1].id").value(7))
                .andExpect(header().string("X-Page", "0"))
                .andExpect(header().string("X-Size", "20"))
                .andExpect(header().string("X-Has-Next", "true"))
                // 첫 페이지를 page 방식으로 받아도 바로 keyset으로 이어갈 수 있도록 마지막 id
                .andExpect(header().string("X-Next-After-Id", "7"));
    }

    @Test
    void afterId를_주면_keyset으로_조회하고_페이지_번호_헤더는_보내지_않는다() throws Exception {
        KeysetQuery query = KeysetQuery.of(7L, 2);
        given(orderService.getOrdersAfter("owner1", query)).willReturn(
                new SliceImpl<>(List.of(order(9L), order(12L)), query.toPageable(), true));

        mockMvc.perform(get("/api/orders").param("afterId", "7").param("size", "2")
                        .header(HttpHeaders.AUTHORIZATION, ownerToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(9))
                .andExpect(jsonPath("$[1].id").value(12))
                .andExpect(header().doesNotExist("X-Page"))
                .andExpect(header().string("X-Size", "2"))
                .andExpect(header().string("X-Has-Next", "true"))
                .andExpect(header().string("X-Next-After-Id", "12"));

        verify(orderService, never()).getOrders(any(), any());
    }

    @Test
    void 마지막_페이지는_다음_위치_헤더가_없다() throws Exception {
        KeysetQuery query = KeysetQuery.of(12L, 20);
        given(orderService.getOrdersAfter("owner1", query)).willReturn(
                new SliceImpl<>(List.of(order(15L)), query.toPageable(), false));

        mockMvc.perform(get("/api/orders").param("afterId", "12")
                        .header(HttpHeaders.AUTHORIZATION, ownerToken()))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Has-Next", "false"))
                .andExpect(header().doesNotExist("X-Next-After-Id"));
    }

    @Test
    void 빈_결과는_빈_배열이고_다음_위치_헤더가_없다() throws Exception {
        KeysetQuery query = KeysetQuery.of(999L, 20);
        given(orderService.getOrdersAfter("owner1", query)).willReturn(
                new SliceImpl<>(List.of(), query.toPageable(), false));

        mockMvc.perform(get("/api/orders").param("afterId", "999")
                        .header(HttpHeaders.AUTHORIZATION, ownerToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isEmpty())
                .andExpect(header().string("X-Has-Next", "false"))
                .andExpect(header().doesNotExist("X-Next-After-Id"));
    }

    @Test
    void page와_afterId를_함께_주면_400() throws Exception {
        mockMvc.perform(get("/api/orders").param("page", "0").param("afterId", "7")
                        .header(HttpHeaders.AUTHORIZATION, ownerToken()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("page와 afterId는 함께 사용할 수 없습니다."));

        verify(orderService, never()).getOrders(any(), any());
        verify(orderService, never()).getOrdersAfter(any(), any());
    }

    @Test
    void afterId나_size가_범위를_벗어나거나_형식이_틀리면_400() throws Exception {
        for (String[] params : List.of(
                new String[]{"afterId", "-1"},
                new String[]{"afterId", "abc"},
                new String[]{"size", "0"},
                new String[]{"size", "101"})) {
            var request = get("/api/orders").header(HttpHeaders.AUTHORIZATION, ownerToken())
                    .param(params[0], params[1]);
            if (params[0].equals("size")) {
                request.param("afterId", "1");
            }
            mockMvc.perform(request).andExpect(status().isBadRequest());
        }

        verify(orderService, never()).getOrdersAfter(any(), any());
    }

    @Test
    void afterId가_0이면_처음부터_조회한다() throws Exception {
        given(orderService.getOrdersAfter(any(), any())).willReturn(new SliceImpl<>(List.of()));

        mockMvc.perform(get("/api/orders").param("afterId", "0")
                        .header(HttpHeaders.AUTHORIZATION, ownerToken()))
                .andExpect(status().isOk());

        verify(orderService).getOrdersAfter("owner1", KeysetQuery.of(0L, 20));
    }

    @Test
    void 토큰이_없으면_401() throws Exception {
        mockMvc.perform(get("/api/orders").param("afterId", "0"))
                .andExpect(status().isUnauthorized());

        verify(orderService, never()).getOrdersAfter(any(), any());
    }
}
