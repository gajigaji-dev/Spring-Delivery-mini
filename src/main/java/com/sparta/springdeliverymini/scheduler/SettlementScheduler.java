package com.sparta.springdeliverymini.scheduler;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import com.sparta.springdeliverymini.config.TimeConfig;
import com.sparta.springdeliverymini.entity.SettlementPeriod;
import com.sparta.springdeliverymini.lock.DistributedLock;
import com.sparta.springdeliverymini.lock.LockUnavailableException;
import com.sparta.springdeliverymini.logging.TraceIdFilter;
import com.sparta.springdeliverymini.service.SettlementAggregationService;
import com.sparta.springdeliverymini.service.SettlementAggregationService.DailyResult;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicLong;

// 일 정산 자동 집계 스케줄러 (활성화는 config.SchedulingConfig)
//
// - 매일 01:10 (Asia/Seoul) 실행 — D일은 D+1 01:00부터 집계 가능, 10분 여유를 둠 (settlement.scheduler.cron)
// - 집계한 정산은 PENDING(확정 대기) → 관리자가 확정·지급 (AdminSettlementService)
// - 마지막 집계 가능 날짜부터 거슬러 catch-up-days일을 집계
//   → 서버가 내려가 있어 실행을 놓친 날도 다음 실행에서 채워짐
//   → 이미 정산이 있는 매장·날짜는 건너뛰므로 여러 번 실행해도 결과가 같음
// - 여러 서버에서 동시에 실행돼도 DB UNIQUE(store_id, settlement_date)로 중복 정산되지 않음
// - 여러 서버 중 한 대만 실행하도록 Redis 분산 락을 잡음 (#41) — 중복 집계 작업·경합 로그를 없애기 위함
//   락을 못 잡은 서버(다른 서버가 실행 중이거나 Redis 장애)는 실행하지 않음
//   Redis 장애로 모든 서버가 건너뛴 날은 다음 실행의 catch-up이 채우고, 그보다 오래 빠지면 관리자 재집계
//   (락은 중복 실행을 줄이는 장치이고, 정산 중복을 막는 최종 근거는 위의 DB UNIQUE)
// - 매장 집계 실패는 실패 기록(settlement_aggregation_failures)으로 남고, 이후 실행에서 성공하면 해결 처리됨
//   catch-up 기간을 넘긴 누락·실패분은 관리자가 재집계 (AdminSettlementService.reaggregate, #39)
// - 메트릭 (monitoring/alerts.yml, docs/architecture.md Redis 장)
//   settlement.scheduler.runs{result}: executed / skipped_locked / skipped_redis_unavailable
//   settlement.scheduler.last.duration.seconds · lock.at.most.seconds: 마지막 실행 시간과 락 TTL
//     → 실행 시간이 TTL에 가까워지는지 실측으로 확인해 lock-at-most-for를 조정
@Slf4j
@Component
public class SettlementScheduler {

    // Redis 키: lock:settlement-daily-aggregation (RedisDistributedLock.KEY_PREFIX)
    static final String LOCK_NAME = "settlement-daily-aggregation";

    private final SettlementAggregationService settlementAggregationService;
    private final Clock clock;
    private final DistributedLock distributedLock;
    private final int catchUpDays;
    private final Duration lockAtMostFor;
    private final Duration lockAtLeastFor;
    private final MeterRegistry meterRegistry;
    // 마지막 실행의 소요 시간(ms) — 일 1회 실행이라 Timer의 최댓값(몇 분 뒤 사라짐) 대신 마지막 값을 그대로 유지
    private final AtomicLong lastDurationMillis = new AtomicLong();

    public SettlementScheduler(SettlementAggregationService settlementAggregationService,
                               Clock clock,
                               DistributedLock distributedLock,
                               MeterRegistry meterRegistry,
                               @Value("${settlement.scheduler.catch-up-days:7}") int catchUpDays,
                               @Value("${settlement.scheduler.lock-at-most-for:30m}") Duration lockAtMostFor,
                               @Value("${settlement.scheduler.lock-at-least-for:5m}") Duration lockAtLeastFor) {
        if (catchUpDays < 1) {
            throw new IllegalArgumentException("settlement.scheduler.catch-up-days는 1 이상이어야 합니다: " + catchUpDays);
        }
        // 설정 실수는 새벽 실행 때가 아니라 기동 시점에 드러나도록 여기서 검사
        if (lockAtMostFor.isNegative() || lockAtMostFor.isZero()
                || lockAtLeastFor.isNegative() || lockAtLeastFor.compareTo(lockAtMostFor) > 0) {
            throw new IllegalArgumentException("settlement.scheduler.lock-at-least-for는 0 이상, lock-at-most-for(0 초과) 이하여야 합니다: "
                    + lockAtLeastFor + " / " + lockAtMostFor);
        }
        this.settlementAggregationService = settlementAggregationService;
        this.clock = clock;
        this.distributedLock = distributedLock;
        this.catchUpDays = catchUpDays;
        this.lockAtMostFor = lockAtMostFor;
        this.lockAtLeastFor = lockAtLeastFor;
        this.meterRegistry = meterRegistry;
        Gauge.builder("settlement.scheduler.last.duration.seconds", lastDurationMillis, v -> v.get() / 1000.0)
                .description("마지막 일 정산 자동 집계 소요 시간 (실행 전에는 0)")
                .register(meterRegistry);
        Gauge.builder("settlement.scheduler.lock.at.most.seconds", lockAtMostFor, Duration::toSeconds)
                .description("일 정산 스케줄러 분산 락 최대 유지 시간 (settlement.scheduler.lock-at-most-for)")
                .register(meterRegistry);
        // 처음 건너뛰기 전에도 0으로 노출 → 알림 규칙(increase)이 첫 건너뜀부터 잡음
        for (String result : new String[]{"executed", "skipped_locked", "skipped_redis_unavailable"}) {
            meterRegistry.counter("settlement.scheduler.runs", "result", result);
        }
        // 락 초과 카운터도 0으로 노출 — 없으면 첫 초과 때 처음 생겨 DistributedLockOverrun 규칙이 놓침 (#67 점검에서 발견)
        meterRegistry.counter(DistributedLock.OVERRUN_METRIC, "lock", LOCK_NAME);
    }

    private void countRun(String result) {
        meterRegistry.counter("settlement.scheduler.runs", "result", result).increment();
    }

    @Scheduled(cron = "${settlement.scheduler.cron:0 10 1 * * *}", zone = TimeConfig.ZONE_ID)
    public void aggregateDailySettlements() {
        // 스케줄러 스레드에는 HTTP 요청이 없어 TraceIdFilter가 traceId를 넣지 않음
        // → 실행마다 새 traceId를 MDC에 넣어, 이 실행에서 나온 로그(서비스의 매장별 실패 로그 포함)를 하나로 묶음
        // 기존 값이 있으면(테스트·수동 호출 등 요청 안에서 호출된 경우) 끝난 뒤 되돌림
        String previous = MDC.get(TraceIdFilter.MDC_KEY);
        MDC.put(TraceIdFilter.MDC_KEY, TraceIdFilter.newTraceId());
        try {
            boolean executed = distributedLock.executeIfAcquired(
                    LOCK_NAME, lockAtMostFor, lockAtLeastFor, this::aggregateRecentDays);
            countRun(executed ? "executed" : "skipped_locked");
            if (!executed) {
                log.info("일 정산 자동 집계 건너뜀 — 다른 인스턴스가 실행 중이거나 방금 실행함 lock={}", LOCK_NAME);
            }
        } catch (LockUnavailableException e) {
            countRun("skipped_redis_unavailable");
            // 락 없이 실행하면 모든 인스턴스가 함께 실행될 수 있어 건너뜀 (데이터는 DB 제약이 지키지만 실행 원칙을 우선)
            log.error("일 정산 자동 집계 건너뜀 — Redis 장애로 분산 락 확인 불가. 다음 실행에서 최근 {}일을 다시 집계함", catchUpDays, e);
        } finally {
            // 스케줄러 스레드는 재사용되므로 다음 실행·다른 작업 로그에 traceId가 남지 않도록 반드시 정리
            if (previous == null) {
                MDC.remove(TraceIdFilter.MDC_KEY);
            } else {
                MDC.put(TraceIdFilter.MDC_KEY, previous);
            }
        }
    }

    // 락을 잡은 인스턴스만 실행 — 마지막 집계 가능 날짜부터 거슬러 catchUpDays일
    private void aggregateRecentDays() {
        LocalDate lastClosed = SettlementPeriod.lastConfirmedDate(LocalDateTime.now(clock));
        log.info("일 정산 자동 집계 시작 lastClosed={}, catchUpDays={}", lastClosed, catchUpDays);
        // 경과 시간은 시스템 시계 변경에 영향받지 않는 nanoTime으로 측정 (업무 시각이 아니므로 Clock 대상 아님)
        long startedAt = System.nanoTime();

        // 오래된 날짜부터 순서대로
        for (int daysAgo = catchUpDays - 1; daysAgo >= 0; daysAgo--) {
            LocalDate date = lastClosed.minusDays(daysAgo);
            try {
                DailyResult result = settlementAggregationService.aggregateDaily(date);
                log.info("일 정산 집계 date={}, created={}, skipped={}, failed={}",
                        result.date(), result.created(), result.skipped(), result.failed());
            } catch (RuntimeException e) {
                // 한 날짜의 오류가 다른 날짜의 집계를 막지 않도록 기록만 하고 계속 진행
                log.error("일 정산 집계 실패 date={}", date, e);
            }
        }

        // 락 TTL 실측 근거 — 실행 시간이 lock-at-most-for의 절반을 넘으면 알림 (monitoring/alerts.yml)
        long elapsedMillis = Duration.ofNanos(System.nanoTime() - startedAt).toMillis();
        lastDurationMillis.set(elapsedMillis);
        log.info("일 정산 자동 집계 종료 elapsedMs={}, lockAtMostFor={}", elapsedMillis, lockAtMostFor);
    }
}
