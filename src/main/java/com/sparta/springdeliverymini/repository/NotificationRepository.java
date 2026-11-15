package com.sparta.springdeliverymini.repository;

import com.sparta.springdeliverymini.entity.Notification;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface NotificationRepository extends JpaRepository<Notification, Long> {

    // 내 알림 목록 — 받는 사람 기준 페이징 (idx_notifications_recipient, V14)
    Slice<Notification> findAllByRecipientUserId(Long recipientUserId, Pageable pageable);

    // 발송할 알림을 행 잠금으로 가져옴 (event.NotificationDispatcher, 같은 트랜잭션에서 발송 결과까지 기록)
    // FOR UPDATE SKIP LOCKED: 여러 인스턴스가 함께 돌아도 한 알림은 한 인스턴스만 발송 (OutboxEventRepository와 같은 방식)
    @Query(value = """
            SELECT * FROM notifications
            WHERE send_status = 'PENDING' AND next_send_at <= :now
            ORDER BY id
            LIMIT :limit
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<Notification> findSendableForUpdate(@Param("now") LocalDateTime now, @Param("limit") int limit);
}
