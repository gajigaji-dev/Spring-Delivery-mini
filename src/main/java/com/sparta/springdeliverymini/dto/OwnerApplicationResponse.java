package com.sparta.springdeliverymini.dto;

import com.sparta.springdeliverymini.entity.OwnerApplication;
import com.sparta.springdeliverymini.entity.OwnerApplicationStatus;
import com.sparta.springdeliverymini.entity.User;

import java.time.LocalDateTime;

// 사장님 전환 신청 응답 — 신청자 본인·관리자 공통
// 처리자는 아이디만 (관리자 내부 id는 신청자에게 필요 없음)
public record OwnerApplicationResponse(
        Long id,
        Long userId,
        String username,
        String businessName,
        OwnerApplicationStatus status,
        String rejectReason,
        LocalDateTime requestedAt,
        LocalDateTime decidedAt,
        String decidedBy
) {
    public static OwnerApplicationResponse from(OwnerApplication application) {
        User user = application.getUser();
        User decider = application.getDecidedBy();
        return new OwnerApplicationResponse(
                application.getId(),
                user.getId(),
                user.getUsername(),
                application.getBusinessName(),
                application.getStatus(),
                application.getRejectReason(),
                application.getRequestedAt(),
                application.getDecidedAt(),
                decider == null ? null : decider.getUsername());
    }
}
