package com.sparta.springdeliverymini.entity;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import jakarta.persistence.*;

import java.time.LocalDateTime;
import java.util.UUID;

// Consumer가 처리한 이벤트 (V13) — event_id 기준 멱등성
// 저장은 repository.ConsumedEventRepository.insertIfAbsent(ON CONFLICT DO NOTHING)로만 함
//   → JPA save로 넣다 PK 위반이 나면 PostgreSQL 트랜잭션 전체가 중단되므로, 충돌을 예외 없이 "0행"으로 받기 위함
// 이 엔티티는 조회·스키마 검사(ddl-auto: validate)용
@Entity
@Table(name = "consumed_events")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ConsumedEvent {

    @Id
    private UUID eventId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 50)
    private OutboxEventType eventType;

    @Column(nullable = false)
    private LocalDateTime consumedAt;
}
