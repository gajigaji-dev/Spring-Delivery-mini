package com.sparta.springdeliverymini.repository;

import com.sparta.springdeliverymini.entity.PaymentIdempotencyKey;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Optional;

public interface PaymentIdempotencyKeyRepository extends JpaRepository<PaymentIdempotencyKey, Long> {

    // 같은 사용자·같은 키의 기록 — 결제를 함께 조회해 처음 응답을 다시 만들 때 추가 쿼리가 나가지 않게 함
    @Query("""
            SELECT k FROM PaymentIdempotencyKey k
            JOIN FETCH k.payment
            WHERE k.user.id = :userId AND k.idempotencyKey = :idempotencyKey
            """)
    Optional<PaymentIdempotencyKey> findByUserIdAndKey(@Param("userId") Long userId,
                                                       @Param("idempotencyKey") String idempotencyKey);

    // 보관 기간이 지난 기록 삭제 (maintenance.RetentionCleaner) — 한 번에 limit건, 삭제한 건수 반환
    // 지워진 뒤의 같은 키 재시도는 새 요청으로 처리 → 이미 결제된 주문이라 DB 검사로 409 (재결제 없음)
    @Transactional
    @Modifying
    @Query(value = """
            DELETE FROM payment_idempotency_keys
            WHERE id IN (
                SELECT id FROM payment_idempotency_keys
                WHERE created_at < :before
                ORDER BY created_at
                LIMIT :limit
                FOR UPDATE SKIP LOCKED)
            """, nativeQuery = true)
    int deleteCreatedBefore(@Param("before") LocalDateTime before, @Param("limit") int limit);
}
