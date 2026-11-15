package com.sparta.springdeliverymini.event;

import com.sparta.springdeliverymini.config.OutboxProperties;
import com.sparta.springdeliverymini.config.TimeConfig;
import com.sparta.springdeliverymini.entity.OutboxEvent;
import com.sparta.springdeliverymini.entity.OutboxEventStatus;
import com.sparta.springdeliverymini.entity.OutboxEventType;
import com.sparta.springdeliverymini.repository.OutboxEventRepository;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.KafkaException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

// OutboxPublisher — 발행 성공·실패 기록·재시도 한도·발행 메시지 형식 (Kafka·DB 없이) (#42)
@ExtendWith(MockitoExtension.class)
@SuppressWarnings("unchecked") // 제네릭 Mock(KafkaTemplate·SendResult·ArgumentCaptor) 생성
class OutboxPublisherTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 14, 12, 0);

    @Mock private OutboxEventRepository outboxEventRepository;
    @Mock private KafkaTemplate<String, String> kafkaTemplate;

    private OutboxPublisher publisher;

    @BeforeEach
    void setUp() {
        OutboxProperties properties = new OutboxProperties("delivery-events",
                new OutboxProperties.Publisher(true, Duration.ofSeconds(1), 100, 3,
                        Duration.ofSeconds(1), Duration.ofMinutes(5), Duration.ofMillis(200)),
                new OutboxProperties.Consumer(3, Duration.ofSeconds(1)));
        Clock clock = Clock.fixed(NOW.atZone(TimeConfig.ZONE).toInstant(), TimeConfig.ZONE);
        publisher = new OutboxPublisher(outboxEventRepository, kafkaTemplate, properties, clock);
    }

    private static OutboxEvent event(long orderId) {
        return OutboxEvent.pending(UUID.randomUUID(), OutboxEventType.PAYMENT_COMPLETED, orderId, "{\"orderId\":" + orderId + "}", NOW);
    }

    private void givenEvents(OutboxEvent... events) {
        given(outboxEventRepository.findPublishableForUpdate(NOW, 100)).willReturn(List.of(events));
    }

    private static CompletableFuture<SendResult<String, String>> success() {
        return CompletableFuture.completedFuture(org.mockito.Mockito.mock(SendResult.class));
    }

    @Test
    void 발행할_이벤트가_없으면_Kafka를_호출하지_않는다() {
        given(outboxEventRepository.findPublishableForUpdate(any(), anyInt())).willReturn(List.of());

        OutboxPublisher.PublishResult result = publisher.publishPending();

        assertThat(result).isEqualTo(new OutboxPublisher.PublishResult(0, 0, 0));
        verify(kafkaTemplate, never()).send(any(ProducerRecord.class));
    }

    @Test
    void 주문_id를_키로_payload와_eventId_헤더를_담아_발행하고_PUBLISHED로_바꾼다() {
        OutboxEvent event = event(10L);
        givenEvents(event);
        given(kafkaTemplate.send(any(ProducerRecord.class))).willReturn(success());

        OutboxPublisher.PublishResult result = publisher.publishPending();

        ArgumentCaptor<ProducerRecord<String, String>> captor = ArgumentCaptor.forClass(ProducerRecord.class);
        verify(kafkaTemplate).send(captor.capture());
        ProducerRecord<String, String> record = captor.getValue();
        assertThat(record.topic()).isEqualTo("delivery-events");
        assertThat(record.key()).isEqualTo("10");
        assertThat(record.value()).isEqualTo("{\"orderId\":10}");
        assertThat(new String(record.headers().lastHeader(OutboxPublisher.HEADER_EVENT_ID).value(), StandardCharsets.UTF_8))
                .isEqualTo(event.getEventId().toString());
        assertThat(new String(record.headers().lastHeader(OutboxPublisher.HEADER_EVENT_TYPE).value(), StandardCharsets.UTF_8))
                .isEqualTo("PAYMENT_COMPLETED");

        assertThat(result).isEqualTo(new OutboxPublisher.PublishResult(1, 0, 0));
        assertThat(event.getStatus()).isEqualTo(OutboxEventStatus.PUBLISHED);
        assertThat(event.getPublishedAt()).isEqualTo(NOW);
    }

    @Test
    void 발행이_실패한_이벤트만_재시도로_남기고_나머지는_발행한다() {
        OutboxEvent ok = event(10L);
        OutboxEvent failing = event(11L);
        givenEvents(ok, failing);
        given(kafkaTemplate.send(any(ProducerRecord.class))).willAnswer(invocation -> {
            ProducerRecord<String, String> record = invocation.getArgument(0);
            return "11".equals(record.key())
                    ? CompletableFuture.failedFuture(new KafkaException("broker down"))
                    : success();
        });

        OutboxPublisher.PublishResult result = publisher.publishPending();

        assertThat(result).isEqualTo(new OutboxPublisher.PublishResult(1, 1, 0));
        assertThat(ok.getStatus()).isEqualTo(OutboxEventStatus.PUBLISHED);
        assertThat(failing.getStatus()).isEqualTo(OutboxEventStatus.PENDING);
        assertThat(failing.getAttemptCount()).isEqualTo(1);
        assertThat(failing.getNextAttemptAt()).isEqualTo(NOW.plusSeconds(1)); // 첫 재시도 간격 = initial-backoff
        assertThat(failing.getLastError()).contains("KafkaException").contains("broker down");
    }

    @Test
    void send가_바로_예외를_던지면_실패로_기록하고_나머지는_이번_주기에_보내지_않는다() {
        // 예: Kafka 접속 불가 → 메타데이터를 max.block.ms 동안 기다린 뒤 send 호출 자체가 예외
        // 나머지까지 보내면 건마다 max.block.ms씩 행 잠금을 쥐게 되므로 다음 주기로 미룸
        OutboxEvent failing = event(11L);
        OutboxEvent notSent = event(12L);
        givenEvents(failing, notSent);
        given(kafkaTemplate.send(any(ProducerRecord.class))).willThrow(new KafkaException("Topic not present in metadata"));

        OutboxPublisher.PublishResult result = publisher.publishPending();

        assertThat(result).isEqualTo(new OutboxPublisher.PublishResult(0, 1, 0));
        assertThat(failing.getAttemptCount()).isEqualTo(1);
        assertThat(failing.getLastError()).contains("Topic not present in metadata");
        // 보내지 않은 이벤트는 시도 횟수 없이 그대로 대기
        assertThat(notSent.getStatus()).isEqualTo(OutboxEventStatus.PENDING);
        assertThat(notSent.getAttemptCount()).isZero();
        assertThat(notSent.getNextAttemptAt()).isEqualTo(NOW);
        verify(kafkaTemplate, times(1)).send(any(ProducerRecord.class));
    }

    @Test
    void 응답이_send_timeout_안에_오지_않으면_실패로_기록한다() {
        OutboxEvent event = event(10L);
        givenEvents(event);
        given(kafkaTemplate.send(any(ProducerRecord.class))).willReturn(new CompletableFuture<>()); // 끝나지 않는 발행

        OutboxPublisher.PublishResult result = publisher.publishPending();

        assertThat(result).isEqualTo(new OutboxPublisher.PublishResult(0, 1, 0));
        assertThat(event.getStatus()).isEqualTo(OutboxEventStatus.PENDING);
        assertThat(event.getLastError()).contains("TimeoutException");
    }

    @Test
    void 실패가_재시도_한도에_닿으면_FAILED로_남기고_간격은_두_배씩_는다() {
        OutboxEvent event = event(10L);
        given(kafkaTemplate.send(any(ProducerRecord.class)))
                .willReturn(CompletableFuture.failedFuture(new KafkaException("broker down")));
        given(outboxEventRepository.findPublishableForUpdate(NOW, 100)).willReturn(List.of(event));

        publisher.publishPending();
        assertThat(event.getNextAttemptAt()).isEqualTo(NOW.plusSeconds(1));
        publisher.publishPending();
        assertThat(event.getNextAttemptAt()).isEqualTo(NOW.plusSeconds(2));
        OutboxPublisher.PublishResult last = publisher.publishPending(); // max-attempts = 3

        assertThat(last).isEqualTo(new OutboxPublisher.PublishResult(0, 0, 1));
        assertThat(event.getStatus()).isEqualTo(OutboxEventStatus.FAILED);
        assertThat(event.getAttemptCount()).isEqualTo(3);
    }
}
