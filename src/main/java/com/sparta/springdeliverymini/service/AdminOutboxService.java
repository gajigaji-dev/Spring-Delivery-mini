package com.sparta.springdeliverymini.service;

import com.sparta.springdeliverymini.dto.OutboxEventResponse;
import com.sparta.springdeliverymini.dto.OutboxRepublishResponse;
import com.sparta.springdeliverymini.dto.PageQuery;
import com.sparta.springdeliverymini.entity.OutboxEvent;
import com.sparta.springdeliverymini.entity.OutboxEventStatus;
import com.sparta.springdeliverymini.entity.Role;
import com.sparta.springdeliverymini.entity.User;
import com.sparta.springdeliverymini.exception.ApiException;
import com.sparta.springdeliverymini.repository.OutboxEventRepository;
import com.sparta.springdeliverymini.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Slice;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;

// 관리자 Outbox 관리 — 상태별 조회, FAILED 이벤트 재발행 (docs/architecture.md Kafka 장)
//
// - 재시도 한도를 넘겨 FAILED가 된 이벤트는 자동으로 다시 나가지 않음 → 원인(Kafka 장애 등)을 해결한 뒤 관리자가 재발행
// - 재발행 = FAILED → PENDING (OutboxEvent.republish, FAILED가 아니면 409). 여기서 Kafka로 직접 보내지 않고
//   OutboxPublisher의 다음 주기에 맡김 → 발행 경로가 하나로 유지되고, 재발행도 같은 재시도·순서 규칙을 따름
// - 이벤트 내용(payload·eventId)은 바꾸지 않음 → 이미 Kafka에 저장됐던 이벤트라면 Consumer가 eventId로 중복을 걸러냄
@Slf4j
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class AdminOutboxService {

    // 일괄 재발행 한 번에 되돌리는 최대 건수 — 한꺼번에 너무 많이 PENDING으로 돌려 발행이 몰리지 않도록
    public static final int MAX_REPUBLISH_LIMIT = 100;

    private final OutboxEventRepository outboxEventRepository;
    private final UserRepository userRepository;
    private final Clock clock;

    // 상태별 목록 (id 오름차순 페이징)
    public Slice<OutboxEventResponse> getEvents(OutboxEventStatus status, PageQuery pageQuery, String username) {
        validateAdmin(username);
        return outboxEventRepository.findAllByStatus(status, pageQuery.toPageable())
                .map(OutboxEventResponse::from);
    }

    // 단건 재발행 — 행 잠금으로 조회해 동시에 요청해도 한 번만 PENDING으로 바뀜 (뒤 요청은 409)
    @Transactional
    public OutboxEventResponse republish(Long id, String username) {
        validateAdmin(username);
        OutboxEvent event = outboxEventRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "이벤트가 없습니다."));
        event.republish(LocalDateTime.now(clock));
        log.info("Outbox 재발행 요청 eventId={}, type={}, aggregateId={}",
                event.getEventId(), event.getEventType(), event.getAggregateId());
        return OutboxEventResponse.from(event);
    }

    // 일괄 재발행 — FAILED 이벤트를 오래된 순으로 limit건
    @Transactional
    public OutboxRepublishResponse republishFailed(int limit, String username) {
        validateAdmin(username);
        if (limit < 1 || limit > MAX_REPUBLISH_LIMIT) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                    "limit: 1 이상 " + MAX_REPUBLISH_LIMIT + " 이하여야 합니다.");
        }
        LocalDateTime now = LocalDateTime.now(clock);
        List<OutboxEvent> events = outboxEventRepository.findFailedForUpdate(limit);
        events.forEach(event -> event.republish(now));
        log.info("Outbox 일괄 재발행 요청 count={}", events.size());
        return new OutboxRepublishResponse(events.size());
    }

    // SecurityConfig에서도 ADMIN만 허용하지만, 토큰 발급 후 역할이 바뀐 경우에 대비해 DB 기준으로 한 번 더 확인
    private void validateAdmin(String username) {
        User currentUser = userRepository.findByUsername(username)
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "존재하지 않는 회원입니다."));
        if (currentUser.getRole() != Role.ADMIN) {
            throw new ApiException(HttpStatus.FORBIDDEN, "관리자만 이벤트를 관리할 수 있습니다.");
        }
    }
}
