package com.sparta.springdeliverymini.dto;

import com.sparta.springdeliverymini.entity.User;

// 정산 작업 처리자 (관리자 전용 응답에만 포함) — 스케줄러가 처리했거나 기록이 없으면 응답에서 null
public record SettlementActorResponse(Long id, String username) {

    public static SettlementActorResponse from(User user) {
        return user == null ? null : new SettlementActorResponse(user.getId(), user.getUsername());
    }
}
