package com.sparta.springdeliverymini.repository;

import com.sparta.springdeliverymini.entity.ConsumedEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.UUID;

public interface ConsumedEventRepository extends JpaRepository<ConsumedEvent, UUID> {

    // 처리 기록을 남기고 1, 이미 처리한 이벤트면 아무것도 하지 않고 0
    // - ON CONFLICT DO NOTHING: PK 위반 예외 없이 처리 → 트랜잭션이 중단되지 않음
    // - 같은 이벤트가 동시에 두 번 들어오면 뒤 요청은 앞 트랜잭션이 끝날 때까지 기다린 뒤
    //   커밋됐으면 0(중복), 롤백됐으면 1(처음 처리)을 받음 → 정확히 한 번만 알림 저장
    @Modifying
    @Query(value = """
            INSERT INTO consumed_events (event_id, event_type, consumed_at)
            VALUES (:eventId, :eventType, :consumedAt)
            ON CONFLICT (event_id) DO NOTHING
            """, nativeQuery = true)
    int insertIfAbsent(@Param("eventId") UUID eventId,
                       @Param("eventType") String eventType,
                       @Param("consumedAt") LocalDateTime consumedAt);

    // 보관 기간이 지난 처리 기록 삭제 (maintenance.RetentionCleaner) — 한 번에 limit건, 삭제한 건수 반환
    // 보관 기간은 같은 메시지가 다시 올 수 있는 기간(Kafka 토픽·DLT 보관 기간)보다 길어야 함
    //   → 그보다 짧으면 지워진 뒤 재전달된 이벤트를 처음 본 이벤트로 처리하려다 uk_notifications_event_recipient 위반으로 DLT로 감
    @Transactional
    @Modifying
    @Query(value = """
            DELETE FROM consumed_events
            WHERE event_id IN (
                SELECT event_id FROM consumed_events
                WHERE consumed_at < :before
                ORDER BY consumed_at
                LIMIT :limit
                FOR UPDATE SKIP LOCKED)
            """, nativeQuery = true)
    int deleteConsumedBefore(@Param("before") LocalDateTime before, @Param("limit") int limit);
}
