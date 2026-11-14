package com.sparta.springdeliverymini.controller;

import lombok.RequiredArgsConstructor;
import com.sparta.springdeliverymini.dto.OrderCreateRequest;
import com.sparta.springdeliverymini.dto.OrderResponse;
import com.sparta.springdeliverymini.dto.OrderStatusUpdateRequest;
import com.sparta.springdeliverymini.dto.PageQuery;
import com.sparta.springdeliverymini.service.OrderService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor // final 필드를 받는 생성자 자동 생성 (생성자 주입)
public class OrderController {

    private final OrderService orderService;

    // COSTOMER만 주문 생성 가능
    // CUSTOMER 권한 확인은 SecurityConfig에서 토큰의 role로 처리 (OWNER → 403)
    @PostMapping("/orders")
    public ResponseEntity<OrderResponse> createOrder(
            @Valid @RequestBody OrderCreateRequest request,
            Authentication authentication) {

        // 주문자는 토큰에서 꺼낸 아이디로 Service에서 조회
        OrderResponse order = orderService.createOrder(request, authentication.getName());

        // 주문 생성 성공 시 201 Created와 생성된 주문 정보 반환
        return ResponseEntity.status(HttpStatus.CREATED).body(order);
    }

    // 주문 목록 — 페이지 단위 (?page=0&size=20, size 최대 100), 본문은 배열 그대로이고 페이지 정보는 응답 헤더
    @GetMapping("/orders")
    public ResponseEntity<List<OrderResponse>> getOrders(
            @RequestParam(defaultValue = PageQuery.DEFAULT_PAGE_PARAM) int page,
            @RequestParam(defaultValue = PageQuery.DEFAULT_SIZE_PARAM) int size,
            Authentication authentication
    ) {
        // JWT에서 현재 로그인한 사용자의 username을 가져와 주문 조회
        return SliceResponse.of(orderService.getOrders(authentication.getName(), PageQuery.of(page, size)));
    }

    // Patch: 주문 삭제가 아닌 주문요청 -> 주문취소로 상태 변경 위함
    @PatchMapping("/orders/{id}/cancel")
    public ResponseEntity<Void> cancelOrder(
            @PathVariable Long id,
            Authentication authentication
    ) {
        orderService.cancelOrder(id, authentication.getName());

        return ResponseEntity.noContent().build();
    }

    // OWNER만 주문 상태 변경 가능 (결제완료 → 주문수락, 주문수락 → 배달완료)
    // 권한·본인 메뉴 여부·상태 전이 검증은 Service에서 처리
    @PatchMapping("/orders/{id}/status")
    public ResponseEntity<Void> statusOrder(
            @PathVariable Long id,
            @Valid @RequestBody OrderStatusUpdateRequest request,
            Authentication authentication
    ) {
        orderService.statusOrder(
                id,
                authentication.getName(),
                request.status()
        );

        // 상태 변경 성공 시 204 No Content
        return ResponseEntity.noContent().build();
    }
}
