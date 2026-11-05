package com.sparta.springdeliverymini.dto;

import com.sparta.springdeliverymini.entity.Store;

import java.time.LocalDateTime;

public record StoreResponse(
        Long id,
        String name,
        Long ownerId,
        LocalDateTime createdAt,  // 매장 생성 시각 (BaseEntity)
        LocalDateTime updatedAt) { // 마지막 수정 시각 (BaseEntity)

    public static StoreResponse from(Store store) {
        return new StoreResponse(
                store.getId(),
                store.getName(),
                store.getOwner().getId(), // LAZY 프록시여도 id 조회는 추가 쿼리 없음
                store.getCreatedAt(),
                store.getUpdatedAt()
        );
    }
}
