package com.sparta.springdeliverymini.scheduler;

import com.sparta.springdeliverymini.config.TimeConfig;
import com.sparta.springdeliverymini.lock.DistributedLock;
import com.sparta.springdeliverymini.lock.LockUnavailableException;
import com.sparta.springdeliverymini.logging.TraceIdFilter;
import com.sparta.springdeliverymini.service.SettlementAggregationService;
import com.sparta.springdeliverymini.service.SettlementAggregationService.DailyResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.MDC;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.*;

// 스케줄러가 집계 가능한 날짜를 골라 최근 N일을 오래된 순으로 집계하는지 검증 (DB·Redis 없이)
// 분산 락 자체(Redis)는 lock.RedisDistributedLockTest, 여러 인스턴스 동시 실행은 SettlementSchedulerLockTest
@ExtendWith(MockitoExtension.class)
class SettlementSchedulerTest {

    private static final Duration AT_MOST = Duration.ofMinutes(30);
    private static final Duration AT_LEAST = Duration.ofMinutes(5);

    // 항상 락을 잡는 락 (락 동작과 무관한 집계 로직 테스트용)
    private static final DistributedLock ALWAYS_ACQUIRED = (name, atMost, atLeast, task) -> {
        task.run();
        return true;
    };

    @Mock
    private SettlementAggregationService aggregationService;

    private SettlementScheduler scheduler(LocalDateTime now, int catchUpDays) {
        return scheduler(now, catchUpDays, ALWAYS_ACQUIRED);
    }

    private SettlementScheduler scheduler(LocalDateTime now, int catchUpDays, DistributedLock lock) {
        Clock clock = Clock.fixed(now.atZone(TimeConfig.ZONE).toInstant(), TimeConfig.ZONE);
        return new SettlementScheduler(aggregationService, clock, lock, catchUpDays, AT_MOST, AT_LEAST);
    }

    private static DailyResult empty(LocalDate date) {
        return new DailyResult(date, 0, 0, 0);
    }

    @Test
    void 새벽_1시_이후_실행하면_어제까지_최근_N일을_오래된_순으로_집계한다() {
        given(aggregationService.aggregateDaily(any())).willAnswer(inv -> empty(inv.getArgument(0)));

        scheduler(LocalDateTime.of(2026, 11, 6, 1, 10), 3).aggregateDailySettlements();

        InOrder inOrder = inOrder(aggregationService);
        inOrder.verify(aggregationService).aggregateDaily(LocalDate.of(2026, 11, 3));
        inOrder.verify(aggregationService).aggregateDaily(LocalDate.of(2026, 11, 4));
        inOrder.verify(aggregationService).aggregateDaily(LocalDate.of(2026, 11, 5));
        verifyNoMoreInteractions(aggregationService);
    }

    @Test
    void 새벽_1시_전에_실행되면_그저께까지만_집계한다() {
        given(aggregationService.aggregateDaily(any())).willAnswer(inv -> empty(inv.getArgument(0)));

        // 11/6 00:30 → 11/5는 아직 집계 전
        scheduler(LocalDateTime.of(2026, 11, 6, 0, 30), 1).aggregateDailySettlements();

        verify(aggregationService).aggregateDaily(LocalDate.of(2026, 11, 4));
        verifyNoMoreInteractions(aggregationService);
    }

    @Test
    void 한_날짜가_실패해도_나머지_날짜는_집계한다() {
        given(aggregationService.aggregateDaily(LocalDate.of(2026, 11, 4))).willThrow(new IllegalStateException("DB 오류"));
        given(aggregationService.aggregateDaily(LocalDate.of(2026, 11, 5))).willReturn(empty(LocalDate.of(2026, 11, 5)));

        scheduler(LocalDateTime.of(2026, 11, 6, 1, 10), 2).aggregateDailySettlements();

        verify(aggregationService).aggregateDaily(LocalDate.of(2026, 11, 5));
    }

    @Test
    void catch_up_days가_1보다_작으면_기동_실패() {
        assertThatThrownBy(() -> scheduler(LocalDateTime.of(2026, 11, 6, 1, 10), 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 락_최소_유지_시간이_최대_유지_시간보다_길면_기동_실패() {
        Clock clock = Clock.systemDefaultZone();
        assertThatThrownBy(() -> new SettlementScheduler(aggregationService, clock, ALWAYS_ACQUIRED, 7,
                Duration.ofMinutes(5), Duration.ofMinutes(10)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SettlementScheduler(aggregationService, clock, ALWAYS_ACQUIRED, 7,
                Duration.ZERO, Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ---- 분산 락 (#41) ----

    @Test
    void 설정한_락_이름과_유지_시간으로_락을_요청한다() {
        DistributedLock lock = mock(DistributedLock.class);
        given(lock.executeIfAcquired(any(), any(), any(), any())).willReturn(true);

        scheduler(LocalDateTime.of(2026, 11, 6, 1, 10), 3, lock).aggregateDailySettlements();

        verify(lock).executeIfAcquired(eq("settlement-daily-aggregation"), eq(AT_MOST), eq(AT_LEAST), any());
    }

    @Test
    void 다른_인스턴스가_락을_잡고_있으면_집계하지_않는다() {
        DistributedLock heldByOther = (name, atMost, atLeast, task) -> false;

        scheduler(LocalDateTime.of(2026, 11, 6, 1, 10), 3, heldByOther).aggregateDailySettlements();

        verifyNoInteractions(aggregationService);
    }

    @Test
    void Redis_장애로_락을_확인할_수_없으면_집계하지_않고_예외도_던지지_않는다() {
        DistributedLock redisDown = (name, atMost, atLeast, task) -> {
            throw new LockUnavailableException("Redis 장애", new RuntimeException());
        };

        scheduler(LocalDateTime.of(2026, 11, 6, 1, 10), 3, redisDown).aggregateDailySettlements();

        verifyNoInteractions(aggregationService);
        assertThat(MDC.get(TraceIdFilter.MDC_KEY)).isNull();
    }

    // ---- traceId(MDC) ----

    @Test
    void 실행_중에는_모든_날짜_집계가_같은_traceId를_쓰고_실행마다_새_traceId가_만들어진다() {
        List<String> seen = new ArrayList<>();
        given(aggregationService.aggregateDaily(any())).willAnswer(inv -> {
            seen.add(MDC.get(TraceIdFilter.MDC_KEY)); // 서비스 실행 시점의 MDC 값
            return empty(inv.getArgument(0));
        });
        SettlementScheduler scheduler = scheduler(LocalDateTime.of(2026, 11, 6, 1, 10), 2);

        scheduler.aggregateDailySettlements();
        scheduler.aggregateDailySettlements();

        assertThat(seen).hasSize(4).allMatch(id -> id != null && id.matches("[0-9a-f]{32}"));
        assertThat(seen.get(0)).isEqualTo(seen.get(1));    // 1회차의 두 날짜는 같은 ID
        assertThat(seen.get(2)).isEqualTo(seen.get(3));    // 2회차의 두 날짜는 같은 ID
        assertThat(seen.get(0)).isNotEqualTo(seen.get(2)); // 실행마다 다른 ID
    }

    @Test
    void 실행이_끝나면_집계가_실패했더라도_MDC의_traceId를_지운다() {
        given(aggregationService.aggregateDaily(any())).willThrow(new IllegalStateException("DB 오류"));

        scheduler(LocalDateTime.of(2026, 11, 6, 1, 10), 2).aggregateDailySettlements();

        assertThat(MDC.get(TraceIdFilter.MDC_KEY)).isNull();
    }

    @Test
    void 요청_안에서_호출되면_끝난_뒤_기존_traceId를_되돌린다() {
        given(aggregationService.aggregateDaily(any())).willAnswer(inv -> empty(inv.getArgument(0)));
        MDC.put(TraceIdFilter.MDC_KEY, "request-trace-0001");
        try {
            scheduler(LocalDateTime.of(2026, 11, 6, 1, 10), 1).aggregateDailySettlements();

            assertThat(MDC.get(TraceIdFilter.MDC_KEY)).isEqualTo("request-trace-0001");
        } finally {
            MDC.remove(TraceIdFilter.MDC_KEY);
        }
    }
}
