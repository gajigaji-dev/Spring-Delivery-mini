package com.sparta.springdeliverymini.event;

import com.sparta.springdeliverymini.entity.OutboxEventStatus;
import com.sparta.springdeliverymini.repository.OutboxEventRepository;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;

// Outbox 상태 메트릭 (monitoring/alerts.yml, docs/architecture.md Kafka 장)
//
// - outbox.events{status=pending|failed}: 발행 대기·발행 포기 건수
// - outbox.oldest.pending.age.seconds: 가장 오래 기다린 PENDING 이벤트의 대기 시간 (없으면 0)
//   → 건수만으로는 "많이 쌓였지만 잘 빠지는 중"과 "발행이 멈춤"을 구분할 수 없어 대기 시간을 함께 봄
// - 값은 수집(scrape) 때마다 DB에서 셈: 여러 인스턴스가 같은 값을 보고, 재시작해도 값이 이어짐
//   PENDING·FAILED는 부분 인덱스(V14)로 세므로 PUBLISHED가 쌓여도 비용이 일정
// - DB 조회가 실패하면 Micrometer가 NaN으로 노출 (수집 자체는 실패하지 않음)
@Component
public class OutboxMetrics {

    public OutboxMetrics(OutboxEventRepository outboxEventRepository, Clock clock, MeterRegistry meterRegistry) {
        Gauge.builder("outbox.events", outboxEventRepository, r -> r.countByStatus(OutboxEventStatus.PENDING))
                .tag("status", "pending")
                .description("발행 대기 중인 Outbox 이벤트 수")
                .register(meterRegistry);
        Gauge.builder("outbox.events", outboxEventRepository, r -> r.countByStatus(OutboxEventStatus.FAILED))
                .tag("status", "failed")
                .description("재시도 한도를 넘겨 자동 발행이 중단된 Outbox 이벤트 수 (관리자 재발행 대상)")
                .register(meterRegistry);
        Gauge.builder("outbox.oldest.pending.age.seconds", outboxEventRepository, r -> {
                    LocalDateTime oldest = r.findOldestCreatedAt(OutboxEventStatus.PENDING);
                    return oldest == null
                            ? 0 : Math.max(0, Duration.between(oldest, LocalDateTime.now(clock)).toSeconds());
                })
                .description("가장 오래된 발행 대기 이벤트의 대기 시간")
                .register(meterRegistry);
    }
}
