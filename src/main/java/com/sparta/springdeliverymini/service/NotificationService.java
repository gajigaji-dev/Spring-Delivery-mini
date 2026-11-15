package com.sparta.springdeliverymini.service;

import com.sparta.springdeliverymini.entity.Notification;
import com.sparta.springdeliverymini.event.DeliveryEventMessage;
import com.sparta.springdeliverymini.repository.ConsumedEventRepository;
import com.sparta.springdeliverymini.repository.NotificationRepository;
import com.sparta.springdeliverymini.repository.UserRepository;
import com.sparta.springdeliverymini.dto.NotificationResponse;
import com.sparta.springdeliverymini.dto.PageQuery;
import com.sparta.springdeliverymini.entity.User;
import com.sparta.springdeliverymini.exception.ApiException;
import org.springframework.data.domain.Slice;
import org.springframework.http.HttpStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;

// 업무 이벤트 → 알림 기록 (#42) — 여기서는 기록만 저장하고, 발송은 event.NotificationDispatcher가 따로 함 (V14)
//
// eventId 기준 멱등성: 처리 기록(consumed_events)과 알림을 한 트랜잭션에 저장
// - 처리 기록이 이미 있으면 아무것도 저장하지 않음 → 재발행·재전달된 같은 이벤트로 알림이 두 번 생기지 않음
// - 알림 저장이 실패하면 처리 기록도 롤백 → 재시도 때 다시 처리됨 (처리 기록만 남고 알림이 빠지는 일 없음)
@Service
@RequiredArgsConstructor
public class NotificationService {

    private final ConsumedEventRepository consumedEventRepository;
    private final NotificationRepository notificationRepository;
    private final UserRepository userRepository;
    private final Clock clock;

    // 내 알림 목록 — 받는 사람이 로그인한 사용자인 기록만 (id 오름차순 페이징, idx_notifications_recipient)
    @Transactional(readOnly = true)
    public Slice<NotificationResponse> getMyNotifications(String username, PageQuery pageQuery) {
        // 토큰은 유효하지만 그 사이 회원이 삭제된 경우 → 401
        User currentUser = userRepository.findByUsername(username)
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "존재하지 않는 회원입니다."));
        return notificationRepository.findAllByRecipientUserId(currentUser.getId(), pageQuery.toPageable())
                .map(NotificationResponse::from);
    }

    // 처음 처리한 이벤트면 알림을 저장하고 true, 이미 처리한 이벤트면 false
    @Transactional
    public boolean handle(DeliveryEventMessage event) {
        LocalDateTime now = LocalDateTime.now(clock);
        if (consumedEventRepository.insertIfAbsent(event.eventId(), event.eventType().name(), now) == 0) {
            return false;
        }
        notificationRepository.saveAll(notificationsOf(event, now));
        return true;
    }

    // 이벤트 종류별 받는 사람·문구
    private static List<Notification> notificationsOf(DeliveryEventMessage e, LocalDateTime now) {
        String amount = String.format("%,d원", e.amount());
        return switch (e.eventType()) {
            case PAYMENT_COMPLETED -> List.of(
                    notification(e, e.customerId(), "주문 #" + e.orderId() + " 결제가 완료되었습니다. (" + amount + ")", now),
                    notification(e, e.ownerId(), "새 주문 #" + e.orderId() + "의 결제가 완료되었습니다. (" + amount + ")", now));
            case PAYMENT_REFUNDED -> List.of(
                    notification(e, e.customerId(), "주문 #" + e.orderId() + " 결제 " + amount + "이 환불되었습니다.", now),
                    notification(e, e.ownerId(), "주문 #" + e.orderId() + " 결제 " + amount + "이 환불되어 다음 정산에서 차감됩니다.", now));
            case DELIVERY_COMPLETED -> List.of(
                    notification(e, e.customerId(), "주문 #" + e.orderId() + " 배달이 완료되었습니다.", now));
        };
    }

    private static Notification notification(DeliveryEventMessage e, Long recipientUserId, String message,
                                             LocalDateTime now) {
        return new Notification(e.eventId(), e.eventType(), recipientUserId, e.orderId(), message, now);
    }
}
