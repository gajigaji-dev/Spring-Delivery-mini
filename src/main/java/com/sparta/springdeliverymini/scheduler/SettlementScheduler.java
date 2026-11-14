package com.sparta.springdeliverymini.scheduler;

import lombok.extern.slf4j.Slf4j;
import com.sparta.springdeliverymini.config.TimeConfig;
import com.sparta.springdeliverymini.entity.SettlementPeriod;
import com.sparta.springdeliverymini.logging.TraceIdFilter;
import com.sparta.springdeliverymini.service.SettlementAggregationService;
import com.sparta.springdeliverymini.service.SettlementAggregationService.DailyResult;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;

// 일 정산 자동 집계 스케줄러 (활성화는 config.SchedulingConfig)
//
// - 매일 01:10 (Asia/Seoul) 실행 — D일은 D+1 01:00부터 집계 가능, 10분 여유를 둠 (settlement.scheduler.cron)
// - 집계한 정산은 PENDING(확정 대기) → 관리자가 확정·지급 (AdminSettlementService)
// - 마지막 집계 가능 날짜부터 거슬러 catch-up-days일을 집계
//   → 서버가 내려가 있어 실행을 놓친 날도 다음 실행에서 채워짐
//   → 이미 정산이 있는 매장·날짜는 건너뛰므로 여러 번 실행해도 결과가 같음
// - 여러 서버에서 동시에 실행돼도 DB UNIQUE(store_id, settlement_date)로 중복 정산되지 않음
// - 매장 집계 실패는 실패 기록(settlement_aggregation_failures)으로 남고, 이후 실행에서 성공하면 해결 처리됨
//   catch-up 기간을 넘긴 누락·실패분은 관리자가 재집계 (AdminSettlementService.reaggregate, #39)
@Slf4j
@Component
public class SettlementScheduler {

    private final SettlementAggregationService settlementAggregationService;
    private final Clock clock;
    private final int catchUpDays;

    public SettlementScheduler(SettlementAggregationService settlementAggregationService,
                               Clock clock,
                               @Value("${settlement.scheduler.catch-up-days:7}") int catchUpDays) {
        if (catchUpDays < 1) {
            throw new IllegalArgumentException("settlement.scheduler.catch-up-days는 1 이상이어야 합니다: " + catchUpDays);
        }
        this.settlementAggregationService = settlementAggregationService;
        this.clock = clock;
        this.catchUpDays = catchUpDays;
    }

    @Scheduled(cron = "${settlement.scheduler.cron:0 10 1 * * *}", zone = TimeConfig.ZONE_ID)
    public void aggregateDailySettlements() {
        // 스케줄러 스레드에는 HTTP 요청이 없어 TraceIdFilter가 traceId를 넣지 않음
        // → 실행마다 새 traceId를 MDC에 넣어, 이 실행에서 나온 로그(서비스의 매장별 실패 로그 포함)를 하나로 묶음
        // 기존 값이 있으면(테스트·수동 호출 등 요청 안에서 호출된 경우) 끝난 뒤 되돌림
        String previous = MDC.get(TraceIdFilter.MDC_KEY);
        MDC.put(TraceIdFilter.MDC_KEY, TraceIdFilter.newTraceId());
        try {
            LocalDate lastClosed = SettlementPeriod.lastConfirmedDate(LocalDateTime.now(clock));
            log.info("일 정산 자동 집계 시작 lastClosed={}, catchUpDays={}", lastClosed, catchUpDays);

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
        } finally {
            // 스케줄러 스레드는 재사용되므로 다음 실행·다른 작업 로그에 traceId가 남지 않도록 반드시 정리
            if (previous == null) {
                MDC.remove(TraceIdFilter.MDC_KEY);
            } else {
                MDC.put(TraceIdFilter.MDC_KEY, previous);
            }
        }
    }
}
