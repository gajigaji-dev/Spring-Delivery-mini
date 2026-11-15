package com.sparta.springdeliverymini.event;

import com.sparta.springdeliverymini.service.NotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

// 업무 이벤트 수신 → 알림 기록 저장 (#42)
// - 메시지 본문은 문자열로 받아 여기서 JSON 해석 → 형식 오류를 InvalidEventMessageException으로 바꿔 재시도 없이 DLT
// - 처리 중 예외(DB 장애 등)는 그대로 던짐 → KafkaConfig 오류 처리기가 재시도 후 DLT
// - 같은 메시지가 다시 와도 NotificationService가 eventId로 걸러냄 (최소 한 번 전달 대비)
// - 리스너는 spring.kafka.listener.auto-startup으로 켜고 끔 (Kafka 없이 돌리는 테스트에서는 끔)
@Slf4j
@Component
@RequiredArgsConstructor
public class NotificationEventListener {

    private final ObjectMapper objectMapper;
    private final NotificationService notificationService;

    @KafkaListener(id = "notification-listener", groupId = "notification", topics = "${outbox.topic}")
    public void onMessage(ConsumerRecord<String, String> record) {
        DeliveryEventMessage message = parse(record.value());
        boolean saved = notificationService.handle(message);
        log.info("이벤트 수신 eventId={}, type={}, orderId={}, partition={}, offset={}, result={}",
                message.eventId(), message.eventType(), message.orderId(), record.partition(), record.offset(),
                saved ? "알림 저장" : "중복 무시");
    }

    private DeliveryEventMessage parse(String value) {
        if (value == null || value.isBlank()) {
            throw new InvalidEventMessageException("본문이 없는 메시지입니다.");
        }
        DeliveryEventMessage message;
        try {
            message = objectMapper.readValue(value, DeliveryEventMessage.class);
        } catch (JacksonException e) {
            throw new InvalidEventMessageException("이벤트 JSON을 해석할 수 없습니다: " + e.getOriginalMessage(), e);
        }
        if (message == null) {
            throw new InvalidEventMessageException("본문이 없는 메시지입니다.");
        }
        message.validate();
        return message;
    }
}
