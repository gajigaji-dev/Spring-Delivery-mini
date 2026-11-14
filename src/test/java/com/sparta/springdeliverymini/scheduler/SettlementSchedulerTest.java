package com.sparta.springdeliverymini.scheduler;

import com.sparta.springdeliverymini.config.TimeConfig;
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
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.*;

// 스케줄러가 집계 가능한 날짜를 골라 최근 N일을 오래된 순으로 집계하는지 검증 (DB 없이)
@ExtendWith(MockitoExtension.class)
class SettlementSchedulerTest {

    @Mock
    private SettlementAggregationService aggregationService;

    private SettlementScheduler scheduler(LocalDateTime now, int catchUpDays) {
        Clock clock = Clock.fixed(now.atZone(TimeConfig.ZONE).toInstant(), TimeConfig.ZONE);
        return new SettlementScheduler(aggregationService, clock, catchUpDays);
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
