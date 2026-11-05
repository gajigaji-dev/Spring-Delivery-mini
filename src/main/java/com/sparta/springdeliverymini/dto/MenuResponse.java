package com.sparta.springdeliverymini.dto;

import com.sparta.springdeliverymini.entity.Menu;

import java.time.LocalDateTime;

public record MenuResponse(
        Long id,
        Long storeId,             // 메뉴를 판매하는 매장
        String name,
        long price,
        String description,
        LocalDateTime createdAt,  // 메뉴 등록 시각 (BaseEntity)
        LocalDateTime updatedAt) { // 마지막 수정 시각 (BaseEntity)

    public static MenuResponse from(Menu menu) {
        return new MenuResponse(
                menu.getId(),
                menu.getStore().getId(), // LAZY 프록시여도 id 조회는 추가 쿼리 없음
                menu.getName(),
                menu.getPrice(),
                menu.getDescription(),
                menu.getCreatedAt(),
                menu.getUpdatedAt()
        );
    }
}
