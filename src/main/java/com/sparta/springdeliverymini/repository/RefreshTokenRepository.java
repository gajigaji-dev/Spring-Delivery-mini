package com.sparta.springdeliverymini.repository;

import com.sparta.springdeliverymini.entity.RefreshToken;
import com.sparta.springdeliverymini.entity.RefreshTokenRevokeReason;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, Long> {

    // 재발급·로그아웃 — 행 잠금(SELECT ... FOR UPDATE)으로 조회 (uk_refresh_tokens_token_hash)
    // 같은 토큰으로 동시에 재발급하면 뒤 요청은 앞 요청이 커밋할 때까지 기다린 뒤 폐기된 상태(ROTATED)를 읽음
    // → 두 요청이 모두 새 토큰을 받는 일 없이 뒤 요청은 재사용으로 처리됨
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT t FROM RefreshToken t WHERE t.tokenHash = :tokenHash")
    Optional<RefreshToken> findByTokenHashForUpdate(@Param("tokenHash") String tokenHash);

    // 계열 전체 폐기 — 아직 폐기되지 않은 행만 (idx_refresh_tokens_family_active)
    // 이미 폐기된 행의 사유·시각은 유지 (재사용 판단 근거)
    // 영속성 컨텍스트에 있는 엔티티는 갱신되지 않으므로 호출 뒤 같은 트랜잭션에서 그 엔티티의 상태를 다시 판단하지 않음
    @Modifying(flushAutomatically = true)
    @Query("""
            UPDATE RefreshToken t SET t.revokedAt = :now, t.revokeReason = :reason
            WHERE t.familyId = :familyId AND t.revokedAt IS NULL
            """)
    int revokeFamily(@Param("familyId") UUID familyId, @Param("now") LocalDateTime now,
                     @Param("reason") RefreshTokenRevokeReason reason);

    // 보관 기간 정리 (V16, maintenance.RetentionCleaner) — 만료 시각이 before보다 이전인 행을 limit건씩 삭제
    // 만료된 토큰은 재발급에 쓸 수 없으므로 지워도 동작이 바뀌지 않음 (지운 값이 다시 오면 모르는 값으로 거부)
    // 재발급 중인 행(FOR UPDATE)은 SKIP LOCKED로 건너뜀 — 다른 테이블 정리와 같은 방식
    @Transactional
    @Modifying
    @Query(value = """
            DELETE FROM refresh_tokens
            WHERE id IN (
                SELECT id FROM refresh_tokens
                WHERE expires_at < :before
                ORDER BY expires_at
                LIMIT :limit
                FOR UPDATE SKIP LOCKED)
            """, nativeQuery = true)
    int deleteExpiredBefore(@Param("before") LocalDateTime before, @Param("limit") int limit);
}
