package com.sparta.springdeliverymini.controller;

import lombok.RequiredArgsConstructor;
import com.sparta.springdeliverymini.dto.MySettlementResponse;
import com.sparta.springdeliverymini.dto.PageQuery;
import com.sparta.springdeliverymini.entity.SettlementStatus;
import com.sparta.springdeliverymini.service.OwnerSettlementService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/settlements")
@RequiredArgsConstructor // final 필드를 받는 생성자 자동 생성 (생성자 주입)
public class SettlementController {

    private final OwnerSettlementService ownerSettlementService;

    // 내 정산 내역 — OWNER만 가능(SecurityConfig), 본인 매장의 정산만 Service에서 조회
    // 조건은 모두 선택: ?storeId=&status=&from=&to= (from·to는 yyyy-MM-dd, 둘 다 포함)
    // 페이지 단위 (?page=0&size=20, size 최대 100), 본문은 배열 그대로이고 페이지 정보는 응답 헤더 (#40과 같은 방식)
    @GetMapping
    public ResponseEntity<List<MySettlementResponse>> getMySettlements(
            @RequestParam(required = false) Long storeId,
            @RequestParam(required = false) SettlementStatus status,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = PageQuery.DEFAULT_PAGE_PARAM) int page,
            @RequestParam(defaultValue = PageQuery.DEFAULT_SIZE_PARAM) int size,
            Authentication authentication) {
        return SliceResponse.of(ownerSettlementService.getMySettlements(
                authentication.getName(), storeId, status, from, to, PageQuery.of(page, size)));
    }
}
