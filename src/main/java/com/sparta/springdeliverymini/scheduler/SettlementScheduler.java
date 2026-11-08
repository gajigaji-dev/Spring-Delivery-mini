package com.sparta.springdeliverymini.scheduler;

import lombok.extern.slf4j.Slf4j;
import com.sparta.springdeliverymini.config.TimeConfig;
import com.sparta.springdeliverymini.entity.SettlementPeriod;
import com.sparta.springdeliverymini.service.SettlementConfirmService;
import com.sparta.springdeliverymini.service.SettlementConfirmService.DailyResult;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;

// 일 정산 자동 확정 스케줄러 (활성화는 config.SchedulingConfig)
//
// - 매일 01:10 (Asia/Seoul) 실행 — D일은 D+1 01:00에 확정 가능, 10분 여유를 둠 (settlement.scheduler.cron)
// - 마지막 확정 가능 날짜부터 거슬러 catch-up-days일을 확정
//   → 서버가 내려가 있어 실행을 놓친 날도 다음 실행에서 채워짐
//   → 이미 확정된 매장·날짜는 건너뛰므로 여러 번 실행해도 결과가 같음
// - 여러 서버에서 동시에 실행돼도 DB UNIQUE(store_id, settlement_date)로 중복 확정되지 않음
@Slf4j
@Component
public class SettlementScheduler {

    private final SettlementConfirmService settlementConfirmService;
    private final Clock clock;
    private final int catchUpDays;

    public SettlementScheduler(SettlementConfirmService settlementConfirmService,
                               Clock clock,
                               @Value("${settlement.scheduler.catch-up-days:7}") int catchUpDays) {
        if (catchUpDays < 1) {
            throw new IllegalArgumentException("settlement.scheduler.catch-up-days는 1 이상이어야 합니다: " + catchUpDays);
        }
        this.settlementConfirmService = settlementConfirmService;
        this.clock = clock;
        this.catchUpDays = catchUpDays;
    }

    @Scheduled(cron = "${settlement.scheduler.cron:0 10 1 * * *}", zone = TimeConfig.ZONE_ID)
    public void confirmDailySettlements() {
        LocalDate lastConfirmed = SettlementPeriod.lastConfirmedDate(LocalDateTime.now(clock));

        // 오래된 날짜부터 순서대로
        for (int daysAgo = catchUpDays - 1; daysAgo >= 0; daysAgo--) {
            LocalDate date = lastConfirmed.minusDays(daysAgo);
            try {
                DailyResult result = settlementConfirmService.confirmDaily(date);
                log.info("일 정산 확정 date={}, confirmed={}, skipped={}, failed={}",
                        result.date(), result.confirmed(), result.skipped(), result.failed());
            } catch (RuntimeException e) {
                // 한 날짜의 오류가 다른 날짜의 확정을 막지 않도록 기록만 하고 계속 진행
                log.error("일 정산 확정 실패 date={}", date, e);
            }
        }
    }
}
