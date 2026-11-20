package com.sparta.springdeliverymini.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.UUID;

// Refresh Token (V15) — 원문은 저장하지 않고 SHA-256 해시만 저장 (service.AuthTokenService)
// - 로그인 1번 = 계열(familyId) 1개. 재발급(rotation)마다 같은 계열에 새 행을 만들고 이전 행은 ROTATED로 폐기
// - 해시·만료 시각은 생성 후 바뀌지 않고, 폐기 상태만 한 번 바뀜 (폐기된 행을 다시 살리는 경로 없음)
// - 회원은 id로만 참조(DB에는 FK): 재발급 때 회원의 현재 역할을 따로 조회하므로 연관 엔티티가 필요 없음
@Entity
@Table(name = "refresh_tokens")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RefreshToken {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, updatable = false)
    private Long userId;

    @Column(nullable = false, updatable = false, unique = true, length = 64)
    private String tokenHash;

    @Column(nullable = false, updatable = false)
    private UUID familyId;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(nullable = false, updatable = false)
    private LocalDateTime expiresAt;

    // 계열 절대 만료 (V16) — 로그인 시각 + absolute-ttl, 같은 계열의 모든 행이 같은 값
    // 재발급해도 이 시각을 넘겨 연장되지 않음 → 계속 써도 로그인은 이 시각에 끝남
    @Column(nullable = false, updatable = false)
    private LocalDateTime familyExpiresAt;

    private LocalDateTime revokedAt;

    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private RefreshTokenRevokeReason revokeReason;

    private RefreshToken(Long userId, String tokenHash, UUID familyId, LocalDateTime createdAt, Duration ttl,
                         LocalDateTime familyExpiresAt) {
        if (userId == null || tokenHash == null || tokenHash.isBlank() || familyId == null || createdAt == null
                || ttl == null || ttl.isNegative() || ttl.isZero()
                || familyExpiresAt == null || !familyExpiresAt.isAfter(createdAt)) {
            throw new IllegalArgumentException("Refresh Token 생성에 필요한 값이 올바르지 않습니다.");
        }
        this.userId = userId;
        this.tokenHash = tokenHash;
        this.familyId = familyId;
        this.createdAt = createdAt;
        this.familyExpiresAt = familyExpiresAt;
        // 개별 수명(ttl)과 계열 절대 만료 중 이른 쪽
        LocalDateTime byTtl = createdAt.plus(ttl);
        this.expiresAt = byTtl.isBefore(familyExpiresAt) ? byTtl : familyExpiresAt;
    }

    // 로그인 — 새 계열의 첫 토큰. 계열 절대 만료 = 로그인 시각 + absoluteTtl
    public static RefreshToken issue(Long userId, String tokenHash, LocalDateTime now, Duration ttl,
                                     Duration absoluteTtl) {
        if (now == null || absoluteTtl == null) {
            throw new IllegalArgumentException("Refresh Token 생성에 필요한 값이 올바르지 않습니다.");
        }
        return new RefreshToken(userId, tokenHash, UUID.randomUUID(), now, ttl, now.plus(absoluteTtl));
    }

    // 재발급 — 같은 계열의 다음 토큰
    // 만료 시각은 재발급 시점부터 다시 ttl이지만 계열 절대 만료를 넘지 않음 (V16)
    // 만료되지 않은 토큰만 재발급하므로(서비스에서 확인) now < familyExpiresAt → 새 토큰의 수명은 항상 0보다 큼
    public RefreshToken rotate(String nextTokenHash, LocalDateTime now, Duration ttl) {
        revoke(now, RefreshTokenRevokeReason.ROTATED);
        return new RefreshToken(userId, nextTokenHash, familyId, now, ttl, familyExpiresAt);
    }

    public boolean isRevoked() {
        return revokedAt != null;
    }

    // 재발급에 이미 쓰인 토큰 → 다시 오면 재사용
    public boolean isRotated() {
        return revokeReason == RefreshTokenRevokeReason.ROTATED;
    }

    // 만료 시각과 같으면 만료로 봄
    public boolean isExpired(LocalDateTime now) {
        return !now.isBefore(expiresAt);
    }

    // 폐기는 한 번만 — 이미 폐기된 토큰을 다시 폐기하면 사유·시각이 덮어써져 재사용 판단 근거가 사라짐
    public void revoke(LocalDateTime now, RefreshTokenRevokeReason reason) {
        if (now == null || reason == null) {
            throw new IllegalArgumentException("폐기 시각과 사유가 필요합니다.");
        }
        if (isRevoked()) {
            throw new IllegalStateException("이미 폐기된 Refresh Token입니다. 사유: " + revokeReason);
        }
        this.revokedAt = now;
        this.revokeReason = reason;
    }
}
