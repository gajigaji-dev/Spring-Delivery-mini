package com.sparta.springdeliverymini.dto;

import com.sparta.springdeliverymini.entity.Menu;

import java.time.LocalDateTime;

public record MenuResponse(
        Long id,
        String name,
        int price,
        String description,
        LocalDateTime createdAt,  // 메뉴 등록 시각 (BaseEntity)
        LocalDateTime updatedAt) { // 마지막 수정 시각 (BaseEntity)

    public static MenuResponse from(Menu menu) {
        return new MenuResponse(
                menu.getId(),
                menu.getName(),
                menu.getPrice(),
                menu.getDescription(),
                menu.getCreatedAt(),
                menu.getUpdatedAt()
        );
    }
}
