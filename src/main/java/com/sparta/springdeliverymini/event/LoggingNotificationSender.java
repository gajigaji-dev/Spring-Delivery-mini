package com.sparta.springdeliverymini.event;

import com.sparta.springdeliverymini.entity.Notification;
import lombok.extern.slf4j.Slf4j;

// 기본 발송기 — 외부 채널(이메일·SMS·푸시) 연동이 없어 로그로만 남김 (빈 등록은 config.BackgroundTaskConfig)
// 실제 채널을 붙일 때는 NotificationSender 구현 빈을 등록하면 이 발송기 대신 쓰임
// 문구에는 주문 번호·금액만 들어 있고, 받는 사람은 id로만 남김 (개인정보 없음)
@Slf4j
public class LoggingNotificationSender implements NotificationSender {

    @Override
    public void send(Notification notification) {
        log.info("알림 발송 notificationId={}, recipientUserId={}, type={}, orderId={}, message={}",
                notification.getId(), notification.getRecipientUserId(), notification.getEventType(),
                notification.getOrderId(), notification.getMessage());
    }
}
