package com.sparta.springdeliverymini.controller;

import com.sparta.springdeliverymini.config.SecurityConfig;
import com.sparta.springdeliverymini.entity.Role;
import com.sparta.springdeliverymini.jwt.JwtProvider;
import com.sparta.springdeliverymini.service.MenuService;
import com.sparta.springdeliverymini.service.OrderService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 요청 본문의 가격·수량이 Java 타입(long·int) 범위를 넘으면
// 값이 잘리거나 넘친 채로 Service에 전달되지 않고 JSON 변환 단계에서 400으로 거부되는지 검증
@WebMvcTest({MenuController.class, OrderController.class})
@Import({SecurityConfig.class, JwtProvider.class})
class AmountInputRangeTest {

    private static final String NOT_READABLE = "요청 본문 형식이 올바르지 않습니다. 값의 형식과 허용된 값을 확인해주세요.";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtProvider jwtProvider;

    @MockitoBean
    private MenuService menuService;

    @MockitoBean
    private OrderService orderService;

    @Test
    void 메뉴_가격이_long_범위를_넘으면_400() throws Exception {
        String token = jwtProvider.createToken("owner1", Role.OWNER);

        mockMvc.perform(post("/api/menus")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "storeId": 1, "name": "치킨", "price": 9223372036854775808, "description": "설명" }
                                """)) // Long.MAX_VALUE + 1
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(NOT_READABLE));

        verify(menuService, never()).createMenu(any(), any());
    }

    @Test
    void 주문_수량이_int_범위를_넘으면_400() throws Exception {
        String token = jwtProvider.createToken("customer1", Role.CUSTOMER);

        mockMvc.perform(post("/api/orders")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "items": [{ "menuId": 1, "quantity": 2147483648 }], "deliveryAddress": "서울시" }
                                """)) // Integer.MAX_VALUE + 1
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(NOT_READABLE));

        verify(orderService, never()).createOrder(any(), any());
    }
}
