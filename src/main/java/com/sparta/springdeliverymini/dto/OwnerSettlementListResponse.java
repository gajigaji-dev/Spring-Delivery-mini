package com.sparta.springdeliverymini.dto;

import java.time.LocalDate;
import java.util.List;

// 매장별 일 정산 기록 목록 (OWNER 전용) — 정산일 순, 기록이 없으면 빈 배열
public record OwnerSettlementListResponse(
        Long storeId,
        String storeName,
        LocalDate from,
        LocalDate to,
        List<OwnerSettlementResponse> settlements) {
}
