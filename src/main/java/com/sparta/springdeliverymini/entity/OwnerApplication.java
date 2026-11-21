package com.sparta.springdeliverymini.entity;

import com.sparta.springdeliverymini.exception.InvalidOwnerApplicationStatusException;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

// 사장님 전환 신청 (V17) — 가입은 모두 CUSTOMER, 관리자가 승인해야 OWNER (docs/api.md 사장님 전환 신청)
//
// - 상태 전이(REQUESTED → APPROVED / REJECTED)는 이 엔티티에서만 판단, 위반은 409
// - 처리자는 요청 값이 아니라 인증된 관리자(DB에서 조회한 User) — Service가 넘김
// - 승인 시 신청자 역할 변경(User.promoteToOwner)도 같은 메서드에서 → 신청만 승인되고 역할이 안 바뀌는 일 없음
@Entity
@Table(name = "owner_applications")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OwnerApplication extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false, updatable = false)
    private User user;

    // 상호 — 관리자가 승인 여부를 판단하는 근거 (실제 사업자 확인은 범위 밖)
    @Column(nullable = false, length = 50, updatable = false)
    private String businessName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private OwnerApplicationStatus status;

    @Column(length = 255)
    private String rejectReason;

    @Column(nullable = false, updatable = false)
    private LocalDateTime requestedAt;

    private LocalDateTime decidedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "decided_by")
    private User decidedBy;

    public OwnerApplication(User user, String businessName, LocalDateTime requestedAt) {
        if (user == null || businessName == null || businessName.isBlank() || requestedAt == null) {
            throw new IllegalArgumentException("사장님 전환 신청에 필요한 값이 없습니다.");
        }
        this.user = user;
        this.businessName = businessName.strip();
        this.status = OwnerApplicationStatus.REQUESTED;
        this.requestedAt = requestedAt;
    }

    // 승인 — 신청자를 OWNER로 전환 (CUSTOMER가 아니면 User가 409)
    public void approve(User admin, LocalDateTime now) {
        requireRequested("승인");
        requireDecider(admin, now);
        user.promoteToOwner();
        this.status = OwnerApplicationStatus.APPROVED;
        this.decidedBy = admin;
        this.decidedAt = now;
    }

    // 거절 — 사유는 신청자에게 그대로 보이므로 필수
    public void reject(User admin, String reason, LocalDateTime now) {
        requireRequested("거절");
        requireDecider(admin, now);
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("거절 사유가 필요합니다.");
        }
        this.status = OwnerApplicationStatus.REJECTED;
        this.rejectReason = reason.strip();
        this.decidedBy = admin;
        this.decidedAt = now;
    }

    private void requireRequested(String action) {
        if (status != OwnerApplicationStatus.REQUESTED) {
            throw new InvalidOwnerApplicationStatusException(
                    "승인 대기 중인 신청만 " + action + "할 수 있습니다. 현재 상태: " + status);
        }
    }

    private static void requireDecider(User admin, LocalDateTime now) {
        // 처리자 없는 승인·거절은 DB CHECK에서도 막지만, 원인을 바로 알 수 있도록 먼저 검사
        if (admin == null || now == null) {
            throw new IllegalArgumentException("처리자와 처리 시각이 필요합니다.");
        }
    }
}
