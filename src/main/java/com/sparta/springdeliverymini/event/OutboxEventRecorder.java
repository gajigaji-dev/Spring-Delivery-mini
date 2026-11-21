package com.sparta.springdeliverymini.event;

import com.sparta.springdeliverymini.entity.*;
import com.sparta.springdeliverymini.repository.OutboxEventRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.UUID;

// 업무 트랜잭션 안에서 Outbox 이벤트 저장 (Transactional Outbox, #42)
// - MANDATORY: 호출하는 업무 트랜잭션(결제·환불·배달완료)에 반드시 참여 → 업무 데이터와 이벤트가 함께 커밋·롤백
//   트랜잭션 밖에서 호출하면 예외 (이벤트만 따로 커밋되는 실수 방지)
// - Kafka에는 접속하지 않음 → Kafka 장애와 무관하게 업무 트랜잭션은 그대로 성공
@Component
@RequiredArgsConstructor
public class OutboxEventRecorder {

    private final OutboxEventRepository outboxEventRepository;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    @Transactional(propagation = Propagation.MANDATORY)
    public void paymentCompleted(Payment payment) {
        record(OutboxEventType.PAYMENT_COMPLETED, payment.getOrder(), payment.getId(), payment.getAmount());
    }

    @Transactional(propagation = Propagation.MANDATORY)
    // 환불 1건마다 (V21 부분 환불 포함) — 알림 금액은 이번 환불 금액
    public void paymentRefunded(PaymentRefund refund) {
        Payment payment = refund.getPayment();
        record(OutboxEventType.PAYMENT_REFUNDED, payment.getOrder(), payment.getId(), refund.getAmount());
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void deliveryCompleted(Order order) {
        record(OutboxEventType.DELIVERY_COMPLETED, order, null, order.getTotalPrice());
    }

    private void record(OutboxEventType type, Order order, Long paymentId, long amount) {
        LocalDateTime now = LocalDateTime.now(clock);
        UUID eventId = UUID.randomUUID();
        DeliveryEventMessage message = new DeliveryEventMessage(
                eventId,
                type,
                order.getId(),
                paymentId,
                order.getStore().getId(),
                order.getUser().getId(),
                order.getStore().getOwner().getId(),
                amount,
                now
        );
        outboxEventRepository.save(
                OutboxEvent.pending(eventId, type, order.getId(), objectMapper.writeValueAsString(message), now));
    }
}
