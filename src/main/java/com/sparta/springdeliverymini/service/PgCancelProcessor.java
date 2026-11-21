package com.sparta.springdeliverymini.service;

import com.sparta.springdeliverymini.config.TossPaymentsProperties;
import com.sparta.springdeliverymini.entity.PgCancelRequest;
import com.sparta.springdeliverymini.entity.PgCancelStatus;
import com.sparta.springdeliverymini.pg.TossApiException;
import com.sparta.springdeliverymini.pg.TossPayment;
import com.sparta.springdeliverymini.pg.TossPaymentsClient;
import com.sparta.springdeliverymini.pg.TossUnknownResultException;
import com.sparta.springdeliverymini.repository.PgCancelRequestRepository;
import com.sparta.springdeliverymini.service.PgCancelService.PgCancelRequested;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;

// PG 결제 취소 실행 (V20) — pg_cancel_requests의 PENDING 행을 토스 취소 API로 처리
//
// 실행 시점
// - 커밋 직후 1회: PgCancelService가 낸 이벤트를 AFTER_COMMIT에서 받아 바로 시도 → 보통은 취소 응답 전에 환불까지 끝남
// - 주기 재시도: config.TossPaymentsConfig의 pg-cancel-retry 작업이 processDue() 호출 (실패·시간 초과·서버 재시작 대비)
//
// 동시 실행·중복 환불
// - 행을 FOR UPDATE SKIP LOCKED로 가져와 임대 시각(lease)까지 미뤄 두고 커밋 → 외부 호출은 트랜잭션 밖에서
//   (토스를 기다리는 동안 DB 커넥션·행 잠금을 잡지 않음. 작업자가 죽으면 임대 만료 뒤 다른 작업자가 다시 시도)
// - 금액을 지정해 취소(cancelAmount, V21 부분 취소). 두 번째 시도부터는 토스 잔액을 먼저 조회해
//   목표 잔액(balance_after) 이하면 이미 반영된 것으로 DONE → 결과 불명 뒤 재시도해도 두 번 환불되지 않음
// - 같은 결제의 요청은 생긴 순서대로 하나씩 (PgCancelRequestRepository) — 잔액 비교의 전제
// - 멱등 키는 시도마다 다르게(cancel-{요청 id}-{시도 번호}): 토스는 같은 키의 오류 응답도 15일간 그대로 돌려주므로
//   일시 오류(PROVIDER_ERROR 등)를 같은 키로 재시도하면 계속 같은 오류를 받음. 중복은 위의 잔액 확인이 막음
@Slf4j
@Component
public class PgCancelProcessor {

    static final String METRIC = "pg.cancel.attempts";

    private final PgCancelRequestRepository repository;
    private final TossPaymentsClient tossPaymentsClient;
    private final TossPaymentsProperties properties;
    private final Clock clock;
    private final MeterRegistry meterRegistry;
    private final TransactionTemplate requiresNew;

    public PgCancelProcessor(PgCancelRequestRepository repository, TossPaymentsClient tossPaymentsClient,
                             TossPaymentsProperties properties, Clock clock, MeterRegistry meterRegistry,
                             PlatformTransactionManager transactionManager) {
        this.repository = repository;
        this.tossPaymentsClient = tossPaymentsClient;
        this.properties = properties;
        this.clock = clock;
        this.meterRegistry = meterRegistry;
        // AFTER_COMMIT 리스너 안의 DB 작업은 끝난 트랜잭션에 붙어 커밋되지 않으므로 항상 새 트랜잭션으로
        this.requiresNew = new TransactionTemplate(transactionManager);
        this.requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        // 처음 실패하기 전에도 0으로 노출 → 알림 규칙(increase)이 첫 실패부터 잡음
        for (String result : List.of("done", "retry", "failed")) {
            meterRegistry.counter(METRIC, "result", result);
        }
    }

    // 가져간 행의 스냅샷 — 트랜잭션 밖(외부 호출)에서는 엔티티 대신 이 값만 씀
    record Claimed(Long id, String paymentKey, String reason, long amount, long balanceAfter, int attempt) {
    }

    // 커밋 직후 바로 1회 시도 — 실패해도 업무 응답(주문 취소 성공)에는 영향 없음, 주기 작업이 다시 시도
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onRequested(PgCancelRequested event) {
        try {
            processNow(event.requestId());
        } catch (RuntimeException e) {
            log.warn("PG 취소 즉시 시도 실패 — 주기 작업이 다시 시도 requestId={}", event.requestId(), e);
        }
    }

    public void processNow(Long requestId) {
        List<Claimed> claimed = requiresNew.execute(status -> {
            LocalDateTime now = LocalDateTime.now(clock);
            return repository.findDueByIdForUpdate(requestId, now).map(request -> List.of(claim(request, now)))
                    .orElse(List.of());
        });
        claimed.forEach(this::attempt);
    }

    // 주기 작업 — 시도할 때가 된 행을 batch-size만큼 처리, 처리한 건수 반환
    public int processDue() {
        List<Claimed> claimed = requiresNew.execute(status -> {
            LocalDateTime now = LocalDateTime.now(clock);
            return repository.findDueForUpdate(now, properties.cancel().batchSize()).stream()
                    .map(request -> claim(request, now))
                    .toList();
        });
        claimed.forEach(this::attempt);
        return claimed.size();
    }

    private Claimed claim(PgCancelRequest request, LocalDateTime now) {
        request.claim(now.plus(properties.cancel().lease()));
        return new Claimed(request.getId(), request.getPaymentKey(), request.getReason(), request.getAmount(),
                request.getBalanceAfter(), request.getAttempts() + 1);
    }

    private void attempt(Claimed claimed) {
        String error;
        try {
            // 앞 시도가 결과를 모르는 채 끝났을 수 있음 → 토스 잔액이 이미 목표 이하면 반영된 것 (다시 보내면 두 번 환불)
            if (claimed.attempt() > 1) {
                TossPayment current = tossPaymentsClient.getPayment(claimed.paymentKey());
                if (applied(current, claimed)) {
                    markDone(claimed);
                    return;
                }
            }
            TossPayment result = tossPaymentsClient.cancel(claimed.paymentKey(), claimed.reason(), claimed.amount(),
                    "cancel-" + claimed.id() + "-" + claimed.attempt());
            if (applied(result, claimed)) {
                markDone(claimed);
                return;
            }
            error = "예상하지 못한 취소 응답 status=" + result.status() + ", balance=" + result.balanceAmount();
        } catch (TossApiException e) {
            if (TossApiException.ALREADY_CANCELED_PAYMENT.equals(e.code())) {
                // 전액 취소된 결제 — 잔액 0이므로 어떤 목표 잔액이든 이미 반영된 것
                markDone(claimed);
                return;
            }
            error = "거절 status=" + e.httpStatus() + ", code=" + e.code();
        } catch (TossUnknownResultException e) {
            // 결과 불명 — 다음 시도에서 잔액부터 확인
            error = e.getMessage();
        }
        markFailed(claimed, error);
    }

    // 이 취소가 토스에 반영됐는지 — 잔액이 목표(balance_after) 이하
    // 같은 결제의 요청은 순서대로 하나씩 처리되므로(앞 요청이 DONE이어야 다음 요청) 잔액 비교로 판단할 수 있음
    private static boolean applied(TossPayment payment, Claimed claimed) {
        if (payment.balanceAmount() != null) {
            return payment.balanceAmount() <= claimed.balanceAfter();
        }
        return payment.isCanceled(); // 잔액이 없는 응답이면 전액 취소 상태로만 판단
    }

    private void markDone(Claimed claimed) {
        requiresNew.executeWithoutResult(status -> repository.findById(claimed.id())
                .ifPresent(request -> request.markDone(LocalDateTime.now(clock))));
        meterRegistry.counter(METRIC, "result", "done").increment();
        log.info("PG 취소 완료 requestId={}, attempt={}", claimed.id(), claimed.attempt());
    }

    private void markFailed(Claimed claimed, String error) {
        TossPaymentsProperties.Cancel cancel = properties.cancel();
        boolean[] failed = {false};
        requiresNew.executeWithoutResult(status -> repository.findById(claimed.id()).ifPresent(request -> {
            LocalDateTime next = LocalDateTime.now(clock).plus(cancel.backoff(request.getAttempts() + 1));
            request.markFailedAttempt(error, next, cancel.maxAttempts());
            failed[0] = request.getStatus() == PgCancelStatus.FAILED;
        }));
        if (failed[0]) {
            meterRegistry.counter(METRIC, "result", "failed").increment();
            // 자동 재시도 중단 — 고객에게 돈이 돌아가지 않은 상태라 운영자 확인이 필요 (monitoring/alerts.yml)
            log.error("PG 취소 최종 실패 — 운영자 확인 필요 requestId={}, attempt={}, error={}",
                    claimed.id(), claimed.attempt(), error);
        } else {
            meterRegistry.counter(METRIC, "result", "retry").increment();
            log.warn("PG 취소 실패 — 재시도 예정 requestId={}, attempt={}, error={}", claimed.id(), claimed.attempt(),
                    error);
        }
    }
}
