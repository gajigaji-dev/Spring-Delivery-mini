package com.sparta.springdeliverymini.controller;

import lombok.RequiredArgsConstructor;
import com.sparta.springdeliverymini.dto.OrderResponse;
import com.sparta.springdeliverymini.dto.StoreCreateRequest;
import com.sparta.springdeliverymini.dto.StoreResponse;
import com.sparta.springdeliverymini.dto.StoreSettlementResponse;
import com.sparta.springdeliverymini.service.OrderService;
import com.sparta.springdeliverymini.service.SettlementService;
import com.sparta.springdeliverymini.service.StoreService;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/stores")
@RequiredArgsConstructor // final 필드를 받는 생성자 자동 생성 (생성자 주입)
public class StoreController {

    private final StoreService storeService;
    private final OrderService orderService;
    private final SettlementService settlementService;

    // 매장 생성 — OWNER 권한 확인은 SecurityConfig에서 토큰의 role로 처리 (CUSTOMER → 403)
    @PostMapping
    public ResponseEntity<StoreResponse> createStore(
            @Valid @RequestBody StoreCreateRequest request,
            Authentication authentication) {

        StoreResponse store = storeService.createStore(request, authentication.getName());

        // 매장 생성 성공 시 201 Created와 생성된 매장 정보 반환
        return ResponseEntity.status(HttpStatus.CREATED).body(store);
    }

    // 전체 매장 목록 (로그인 없이 조회 가능)
    @GetMapping
    public List<StoreResponse> getStores() {
        return storeService.getStores();
    }

    // 매장 상세 조회 (로그인 없이 조회 가능), 없는 매장은 Service에서 404
    @GetMapping("/{id}")
    public StoreResponse getStore(@PathVariable Long id) {
        return storeService.getStore(id);
    }

    // 매장별 주문 조회 — OWNER만 가능(SecurityConfig), 본인 매장인지는 Service에서 확인 (다른 매장 → 403)
    @GetMapping("/{id}/orders")
    public List<OrderResponse> getStoreOrders(
            @PathVariable Long id,
            Authentication authentication) {
        return orderService.getStoreOrders(id, authentication.getName());
    }

    // 매장별 기간 정산 조회 — OWNER만 가능(SecurityConfig), 본인 매장인지·기간 규칙은 Service에서 확인
    // from, to: yyyy-MM-dd, 둘 다 포함하는 날짜 (예: ?from=2026-11-01&to=2026-11-05)
    @GetMapping("/{id}/settlements")
    public StoreSettlementResponse getStoreSettlement(
            @PathVariable Long id,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            Authentication authentication) {
        return settlementService.getStoreSettlement(id, from, to, authentication.getName());
    }
}
