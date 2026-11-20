package com.sparta.springdeliverymini.repository;

import com.sparta.springdeliverymini.entity.Notification;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface NotificationRepository extends JpaRepository<Notification, Long> {

    // 내 알림 목록 — 받는 사람 기준 페이징 (idx_notifications_recipient, V14)
    Slice<Notification> findAllByRecipientUserId(Long recipientUserId, Pageable pageable);

    // 안 읽은 알림 개수 (V15, idx_notifications_recipient_unread 부분 인덱스)
    long countByRecipientUserIdAndReadAtIsNull(Long recipientUserId);

    // 읽음 처리 (V15) — 안 읽은 경우에만 read_at 기록, 이미 읽었으면 0건(처음 읽은 시각 유지)
    // read_at 컬럼만 바꾸는 조건부 UPDATE: 발송기가 같은 행의 발송 상태를 바꾸는 것과 서로 덮어쓰지 않음 (Notification.readAt 주석)
    @Modifying
    @Query(value = "UPDATE notifications SET read_at = :now WHERE id = :id AND read_at IS NULL", nativeQuery = true)
    int markReadIfUnread(@Param("id") Long id, @Param("now") LocalDateTime now);

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
