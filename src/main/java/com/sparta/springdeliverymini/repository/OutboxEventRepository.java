package com.sparta.springdeliverymini.repository;

import com.sparta.springdeliverymini.entity.OutboxEvent;
import com.sparta.springdeliverymini.entity.OutboxEventStatus;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, Long> {

    // 발행할 이벤트를 행 잠금으로 가져옴 (event.OutboxPublisher, 같은 트랜잭션에서 발행 결과까지 기록)
    // - FOR UPDATE SKIP LOCKED: 여러 인스턴스가 동시에 돌면 다른 인스턴스가 잡은 행은 건너뜀
    //   → 같은 이벤트를 두 인스턴스가 동시에 발행하지 않음 (별도 분산 락 없이 DB만으로 나눠 가짐)
    // - 업무 테이블이 아닌 outbox_events 행만 잠그므로 결제·환불 트랜잭션을 기다리게 하지 않음
    // - 순서 보장(NOT EXISTS): 같은 주문의 앞 이벤트가 아직 PENDING이면 뒤 이벤트는 가져오지 않음
    //   → 앞 이벤트가 재시도 대기 중일 때 뒤 이벤트가 먼저 발행되는 순서 역전을 막음
    //   → 한 batch에는 주문당 이벤트가 하나만 들어가므로, batch 안에서 앞 이벤트만 실패하는 경우도 없음
    //   다른 인스턴스가 앞 이벤트를 잡고 발행 중이어도 커밋 전까지 PENDING으로 보이므로 뒤 이벤트는 대기
    //   FAILED(재시도 한도 초과)는 막지 않음 — 한 건의 실패가 그 주문의 이후 알림을 계속 막지 않도록
    //   (FAILED를 나중에 재발행하면 늦게 도착할 수 있음 → Consumer는 occurredAt으로 판단, docs/architecture.md Kafka 장)
    //   idx_outbox_events_pending_aggregate (V14) 사용
    @Query(value = """
            SELECT e.* FROM outbox_events e
            WHERE e.status = 'PENDING' AND e.next_attempt_at <= :now
              AND NOT EXISTS (
                  SELECT 1 FROM outbox_events earlier
                  WHERE earlier.aggregate_type = e.aggregate_type
                    AND earlier.aggregate_id = e.aggregate_id
                    AND earlier.status = 'PENDING'
                    AND earlier.id < e.id)
            ORDER BY e.id
            LIMIT :limit
            FOR UPDATE OF e SKIP LOCKED
            """, nativeQuery = true)
    List<OutboxEvent> findPublishableForUpdate(@Param("now") LocalDateTime now, @Param("limit") int limit);

    // 관리자 조회 — 상태별 목록 (id 오름차순 페이징)
    Slice<OutboxEvent> findAllByStatus(OutboxEventStatus status, Pageable pageable);

    // 관리자 재발행(단건) — 행 잠금. 같은 이벤트를 동시에 재발행해도 한 번만 PENDING으로 바뀜
    @Query(value = "SELECT * FROM outbox_events WHERE id = :id FOR UPDATE", nativeQuery = true)
    Optional<OutboxEvent> findByIdForUpdate(@Param("id") Long id);

    // 관리자 재발행(여러 건) — FAILED 이벤트를 오래된 순으로 limit건 잠금 (idx_outbox_events_failed, V14)
    @Query(value = """
            SELECT * FROM outbox_events
            WHERE status = 'FAILED'
            ORDER BY id
            LIMIT :limit
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<OutboxEvent> findFailedForUpdate(@Param("limit") int limit);

    // 메트릭용 (event.OutboxMetrics) — PENDING·FAILED는 부분 인덱스로 셈
    long countByStatus(OutboxEventStatus status);

    // 가장 오래 기다린 이벤트의 생성 시각 (발행 지연 메트릭) — 없으면 null
    @Query("SELECT MIN(e.createdAt) FROM OutboxEvent e WHERE e.status = :status")
    LocalDateTime findOldestCreatedAt(@Param("status") OutboxEventStatus status);

    // 보관 기간이 지난 PUBLISHED 이벤트 삭제 (maintenance.RetentionCleaner) — 한 번에 limit건, 삭제한 건수 반환
    // - PENDING·FAILED는 지우지 않음 (아직 발행되지 않은 이벤트)
    // - 한 번에 조금씩 지우는 이유: 오래 쌓인 행을 한 문장으로 지우면 긴 트랜잭션·큰 WAL이 생김
    @Transactional
    @Modifying
    @Query(value = """
            DELETE FROM outbox_events
            WHERE id IN (
                SELECT id FROM outbox_events
                WHERE status = 'PUBLISHED' AND published_at < :before
                ORDER BY published_at
                LIMIT :limit
                FOR UPDATE SKIP LOCKED)
            """, nativeQuery = true)
    int deletePublishedBefore(@Param("before") LocalDateTime before, @Param("limit") int limit);
}
