package com.sparta.springdeliverymini.entity;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import jakarta.persistence.*;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.UUID;

// 알림 기록 (V13) — Kafka 이벤트를 받아 "누구에게 무엇을 알릴지" 저장
// - 내용(받는 사람·문구)은 생성 후 변경하지 않고, 발송 상태만 UPDATE (V14)
//   발송은 event.NotificationDispatcher가 PENDING 기록을 읽어 event.NotificationSender로 보냄
// - 받는 사람은 id로만 저장(FK 없음): 이벤트 payload의 스냅샷 값이며, 회원이 지워져도 기록은 남김
// - (event_id, recipient_user_id) UNIQUE — 같은 이벤트로 같은 사람에게 두 번 저장되지 않음
@Entity
@Table(name = "notifications",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_notifications_event_recipient", columnNames = {"event_id", "recipient_user_id"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Notification {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, updatable = false)
    private UUID eventId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 50)
    private OutboxEventType eventType;

    @Column(nullable = false, updatable = false)
    private Long recipientUserId;

    @Column(nullable = false, updatable = false)
    private Long orderId;

    @Column(nullable = false, updatable = false)
    private String message;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    static final int MAX_ERROR_LENGTH = 500;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private NotificationSendStatus sendStatus;

    // 발송 실패 횟수 (성공한 시도는 세지 않음)
    @Column(nullable = false)
    private int sendAttemptCount;

    // 이 시각 이후 발송 시도 (실패하면 재시도 간격만큼 뒤로 밀림)
    @Column(nullable = false)
    private LocalDateTime nextSendAt;

    // SENT일 때만 있음 (DB CHECK)
    private LocalDateTime sentAt;

    @Column(length = MAX_ERROR_LENGTH)
    private String lastSendError;

    // 읽은 시각 (V15, NULL = 안 읽음) — 엔티티 저장으로는 쓰지 않고 조건부 UPDATE로만 기록
    // (NotificationRepository.markReadIfUnread)
    // 이유: @DynamicUpdate가 없어 엔티티 저장은 모든 컬럼을 UPDATE함 → 읽음 처리를 엔티티로 하면
    //   발송기(NotificationDispatcher)가 같은 행의 발송 상태를 바꾸는 중에 오래된 send_status를 덮어쓸 수 있고,
    //   반대로 발송기의 저장이 read_at을 NULL로 되돌릴 수 있음 → insertable·updatable=false로 두 경로를 분리
    @Column(insertable = false, updatable = false)
    private LocalDateTime readAt;

    public Notification(UUID eventId, OutboxEventType eventType, Long recipientUserId, Long orderId,
                        String message, LocalDateTime createdAt) {
        if (eventId == null || eventType == null || recipientUserId == null || orderId == null
                || message == null || message.isBlank() || createdAt == null) {
            throw new IllegalArgumentException("알림 기록에 필요한 값이 없습니다.");
        }
        this.eventId = eventId;
        this.eventType = eventType;
        this.recipientUserId = recipientUserId;
        this.orderId = orderId;
        this.message = message;
        this.createdAt = createdAt;
        // 만들자마자 발송 대상
        this.sendStatus = NotificationSendStatus.PENDING;
        this.sendAttemptCount = 0;
        this.nextSendAt = createdAt;
    }

    public boolean isRead() {
        return readAt != null;
    }

    // 받는 사람 본인인지 — 읽음 처리 권한 판단 (다른 사람의 알림은 403)
    public boolean isRecipient(Long userId) {
        return recipientUserId.equals(userId);
    }

    public boolean isSendPending() {
        return sendStatus == NotificationSendStatus.PENDING;
    }

    // 발송 확인 → SENT (PENDING에서만)
    public void markSent(LocalDateTime sentAt) {
        if (sentAt == null) {
            throw new IllegalArgumentException("발송 시각이 필요합니다.");
        }
        requireSendPending();
        this.sendStatus = NotificationSendStatus.SENT;
        this.sentAt = sentAt;
    }

    // 발송 실패 — 실패 횟수가 maxAttempts에 닿으면 FAILED(자동 발송 중단), 아니면 retryDelay 뒤 재시도
    public void recordSendFailure(String error, LocalDateTime failedAt, int maxAttempts, Duration retryDelay) {
        if (failedAt == null || retryDelay == null || retryDelay.isNegative() || maxAttempts < 1) {
            throw new IllegalArgumentException("발송 실패 기록에 필요한 값이 올바르지 않습니다.");
        }
        requireSendPending();
        this.sendAttemptCount = Math.addExact(sendAttemptCount, 1);
        this.lastSendError = error == null || error.isBlank() ? "unknown"
                : error.length() <= MAX_ERROR_LENGTH ? error : error.substring(0, MAX_ERROR_LENGTH);
        if (sendAttemptCount >= maxAttempts) {
            this.sendStatus = NotificationSendStatus.FAILED;
        } else {
            this.nextSendAt = failedAt.plus(retryDelay);
        }
    }

    private void requireSendPending() {
        if (!isSendPending()) {
            throw new IllegalStateException("발송 대기(PENDING) 상태의 알림만 처리할 수 있습니다. 현재 상태: " + sendStatus);
        }
    }
}
