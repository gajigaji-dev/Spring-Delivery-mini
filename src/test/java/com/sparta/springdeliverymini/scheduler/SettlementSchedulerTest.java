package com.sparta.springdeliverymini.scheduler;

import com.sparta.springdeliverymini.config.TimeConfig;
import com.sparta.springdeliverymini.service.SettlementConfirmService;
import com.sparta.springdeliverymini.service.SettlementConfirmService.DailyResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.*;

// 스케줄러가 확정 가능한 날짜를 골라 최근 N일을 오래된 순으로 확정하는지 검증 (DB 없이)
@ExtendWith(MockitoExtension.class)
class SettlementSchedulerTest {

    @Mock
    private SettlementConfirmService confirmService;

    private SettlementScheduler scheduler(LocalDateTime now, int catchUpDays) {
        Clock clock = Clock.fixed(now.atZone(TimeConfig.ZONE).toInstant(), TimeConfig.ZONE);
        return new SettlementScheduler(confirmService, clock, catchUpDays);
    }

    private static DailyResult empty(LocalDate date) {
        return new DailyResult(date, 0, 0, 0);
    }

    @Test
    void 새벽_1시_이후_실행하면_어제까지_최근_N일을_오래된_순으로_확정한다() {
        given(confirmService.confirmDaily(any())).willAnswer(inv -> empty(inv.getArgument(0)));

        scheduler(LocalDateTime.of(2026, 11, 6, 1, 10), 3).confirmDailySettlements();

        InOrder inOrder = inOrder(confirmService);
        inOrder.verify(confirmService).confirmDaily(LocalDate.of(2026, 11, 3));
        inOrder.verify(confirmService).confirmDaily(LocalDate.of(2026, 11, 4));
        inOrder.verify(confirmService).confirmDaily(LocalDate.of(2026, 11, 5));
        verifyNoMoreInteractions(confirmService);
    }

    @Test
    void 새벽_1시_전에_실행되면_그저께까지만_확정한다() {
        given(confirmService.confirmDaily(any())).willAnswer(inv -> empty(inv.getArgument(0)));

        // 11/6 00:30 → 11/5는 아직 확정 전
        scheduler(LocalDateTime.of(2026, 11, 6, 0, 30), 1).confirmDailySettlements();

        verify(confirmService).confirmDaily(LocalDate.of(2026, 11, 4));
        verifyNoMoreInteractions(confirmService);
    }

    @Test
    void 한_날짜가_실패해도_나머지_날짜는_확정한다() {
        given(confirmService.confirmDaily(LocalDate.of(2026, 11, 4))).willThrow(new IllegalStateException("DB 오류"));
        given(confirmService.confirmDaily(LocalDate.of(2026, 11, 5))).willReturn(empty(LocalDate.of(2026, 11, 5)));

        scheduler(LocalDateTime.of(2026, 11, 6, 1, 10), 2).confirmDailySettlements();

        verify(confirmService).confirmDaily(LocalDate.of(2026, 11, 5));
    }

    @Test
    void catch_up_days가_1보다_작으면_기동_실패() {
        assertThatThrownBy(() -> scheduler(LocalDateTime.of(2026, 11, 6, 1, 10), 0))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
