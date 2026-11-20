package com.sparta.springdeliverymini.entity;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// Refresh Token 상태 규칙 — 발급·재발급(같은 계열)·폐기 1회·만료 경계
class RefreshTokenTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 20, 12, 0);
    private static final Duration TTL = Duration.ofDays(14);
    private static final Duration ABSOLUTE = Duration.ofDays(30);

    @Test
    void 계열_절대_만료는_로그인_시각_기준이고_재발급해도_넘어가지_않는다() {
        RefreshToken first = RefreshToken.issue(1L, "hash-1", NOW, TTL, ABSOLUTE);
        assertThat(first.getFamilyExpiresAt()).isEqualTo(NOW.plus(ABSOLUTE));

        // 20일째 재발급: 재발급 시각 + 14일(34일째)이 아니라 계열 절대 만료(30일째)로 잘림
        RefreshToken second = first.rotate("hash-2", NOW.plusDays(20), TTL);
        assertThat(second.getFamilyExpiresAt()).isEqualTo(NOW.plus(ABSOLUTE));
        assertThat(second.getExpiresAt()).isEqualTo(NOW.plus(ABSOLUTE));

        // 10일째 재발급이면 아직 ttl이 더 이름
        RefreshToken other = RefreshToken.issue(1L, "hash-3", NOW, TTL, ABSOLUTE)
                .rotate("hash-4", NOW.plusDays(10), TTL);
        assertThat(other.getExpiresAt()).isEqualTo(NOW.plusDays(24));
    }

    @Test
    void 절대_만료가_ttl보다_짧으면_첫_토큰부터_절대_만료에_맞춰진다() {
        RefreshToken token = RefreshToken.issue(1L, "hash-1", NOW, TTL, Duration.ofDays(7));

        assertThat(token.getExpiresAt()).isEqualTo(NOW.plusDays(7));
    }

    @Test
    void 발급하면_새_계열이고_수명만큼_뒤에_만료된다() {
        RefreshToken token = RefreshToken.issue(1L, "hash-1", NOW, TTL, ABSOLUTE);

        assertThat(token.getFamilyId()).isNotNull();
        assertThat(token.getExpiresAt()).isEqualTo(NOW.plus(TTL));
        assertThat(token.isRevoked()).isFalse();
        assertThat(RefreshToken.issue(1L, "hash-2", NOW, TTL, ABSOLUTE).getFamilyId()).isNotEqualTo(token.getFamilyId());
    }

    @Test
    void 만료_시각과_같으면_만료로_본다() {
        RefreshToken token = RefreshToken.issue(1L, "hash-1", NOW, TTL, ABSOLUTE);

        assertThat(token.isExpired(NOW.plus(TTL).minusNanos(1000))).isFalse();
        assertThat(token.isExpired(NOW.plus(TTL))).isTrue();
    }

    @Test
    void 재발급하면_이전_토큰은_ROTATED로_폐기되고_같은_계열의_새_토큰이_수명을_새로_받는다() {
        RefreshToken current = RefreshToken.issue(1L, "hash-1", NOW, TTL, ABSOLUTE);
        LocalDateTime later = NOW.plusDays(3);

        RefreshToken next = current.rotate("hash-2", later, TTL);

        assertThat(current.isRevoked()).isTrue();
        assertThat(current.isRotated()).isTrue();
        assertThat(current.getRevokedAt()).isEqualTo(later);
        assertThat(next.getFamilyId()).isEqualTo(current.getFamilyId());
        assertThat(next.getUserId()).isEqualTo(1L);
        assertThat(next.getExpiresAt()).isEqualTo(later.plus(TTL));
        assertThat(next.isRevoked()).isFalse();
    }

    @Test
    void 이미_폐기된_토큰은_다시_폐기하거나_재발급할_수_없다() {
        RefreshToken token = RefreshToken.issue(1L, "hash-1", NOW, TTL, ABSOLUTE);
        token.revoke(NOW, RefreshTokenRevokeReason.LOGOUT);

        // 사유가 덮어써지면 재사용 판단 근거가 사라짐
        assertThatThrownBy(() -> token.revoke(NOW.plusMinutes(1), RefreshTokenRevokeReason.REUSE_DETECTED))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> token.rotate("hash-2", NOW, TTL))
                .isInstanceOf(IllegalStateException.class);
        assertThat(token.getRevokeReason()).isEqualTo(RefreshTokenRevokeReason.LOGOUT);
        assertThat(token.isRotated()).isFalse();
    }

    @Test
    void 필수_값이_없거나_수명이_0이하면_만들_수_없다() {
        assertThatThrownBy(() -> RefreshToken.issue(null, "hash", NOW, TTL, ABSOLUTE)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RefreshToken.issue(1L, " ", NOW, TTL, ABSOLUTE)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RefreshToken.issue(1L, "hash", NOW, Duration.ZERO, ABSOLUTE))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
