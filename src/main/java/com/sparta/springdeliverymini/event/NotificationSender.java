package com.sparta.springdeliverymini.event;

import com.sparta.springdeliverymini.entity.Notification;

// 알림 발송 채널 (이메일·SMS·푸시 등) — 알림 기록 1건을 받는 사람에게 보냄
// - 실패는 예외로 알림 → NotificationDispatcher가 실패 횟수를 기록하고 재시도
// - 같은 알림이 두 번 올 수 있음(발송 후 결과 기록 전에 서버가 죽은 경우) → 채널 쪽에서 notification id로 중복을 거를 수 있음
public interface NotificationSender {

    void send(Notification notification);
}
