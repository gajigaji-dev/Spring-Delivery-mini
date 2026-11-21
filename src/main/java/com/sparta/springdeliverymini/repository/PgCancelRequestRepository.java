package com.sparta.springdeliverymini.repository;

import com.sparta.springdeliverymini.entity.PgCancelRequest;
import com.sparta.springdeliverymini.entity.PgCancelStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface PgCancelRequestRepository extends JpaRepository<PgCancelRequest, Long> {

    // 시도할 때가 된 PENDING 행을 잠그고 가져옴 — 다른 작업자가 잡은 행은 건너뜀(SKIP LOCKED)
    // 가져간 뒤 같은 트랜잭션에서 임대 시각(next_attempt_at)을 미뤄 두므로, 커밋 후 외부 호출 중에도 다른 작업자가 가져가지 않음
    // idx_pg_cancel_requests_pending(V20) 사용
    // 같은 결제 키의 앞 요청이 아직 DONE이 아니면(PENDING·FAILED) 건너뜀 — 결제 하나의 취소는 생긴 순서대로 하나씩
    //   (잔액 비교로 이미 반영됐는지 판단하므로 순서가 섞이면 안 됨. 앞 요청이 FAILED면 운영자가 처리할 때까지 뒤 요청도 멈춤)
    @Query(value = """
            SELECT * FROM pg_cancel_requests r
            WHERE r.status = 'PENDING' AND r.next_attempt_at <= :now
              AND NOT EXISTS (SELECT 1 FROM pg_cancel_requests e
                              WHERE e.payment_key = r.payment_key AND e.id < r.id AND e.status <> 'DONE')
            ORDER BY r.next_attempt_at, r.id
            LIMIT :limit
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<PgCancelRequest> findDueForUpdate(@Param("now") LocalDateTime now, @Param("limit") int limit);

    // 커밋 직후 바로 시도할 1건 — 이미 다른 작업자가 잡았거나(잠금·임대 중) 끝난 행이면 빈 값
    @Query(value = """
            SELECT * FROM pg_cancel_requests r
            WHERE r.id = :id AND r.status = 'PENDING' AND r.next_attempt_at <= :now
              AND NOT EXISTS (SELECT 1 FROM pg_cancel_requests e
                              WHERE e.payment_key = r.payment_key AND e.id < r.id AND e.status <> 'DONE')
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    Optional<PgCancelRequest> findDueByIdForUpdate(@Param("id") Long id, @Param("now") LocalDateTime now);

    // 환불 없는 전액 취소(주문 취소·보상) — 결제 키당 1건 (부분 유니크 인덱스)
    Optional<PgCancelRequest> findByPaymentKeyAndPaymentRefundIsNull(String paymentKey);

    List<PgCancelRequest> findAllByPaymentKeyOrderByIdAsc(String paymentKey);

    long countByStatus(PgCancelStatus status);
}
