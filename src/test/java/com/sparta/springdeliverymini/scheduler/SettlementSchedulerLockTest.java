package com.sparta.springdeliverymini.scheduler;

import com.sparta.springdeliverymini.RedisTestSupport;
import com.sparta.springdeliverymini.config.TimeConfig;
import com.sparta.springdeliverymini.lock.RedisDistributedLock;
import com.sparta.springdeliverymini.service.SettlementAggregationService;
import com.sparta.springdeliverymini.service.SettlementAggregationService.DailyResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

// 여러 애플리케이션 인스턴스의 스케줄러가 같은 시각에 깨어나도 한 인스턴스만 집계하는지 실제 Redis로 검증 (#41)
// 집계 서비스는 Mock (정산 중복이 DB 제약으로 막히는 것은 SettlementAuditRecoveryIntegrationTest 등에서 검증)
@ExtendWith(MockitoExtension.class)
class SettlementSchedulerLockTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 6, 1, 10);
    private static final int CATCH_UP_DAYS = 3;
    private static final String LOCK_KEY = "lock:" + SettlementScheduler.LOCK_NAME;

    @Mock
    private SettlementAggregationService aggregationService;

    @AfterEach
    void tearDown() {
        RedisTestSupport.resetAfterPause();
    }

    // 인스턴스 하나 = 자기 Redis 연결 + 자기 락 + 자기 스케줄러
    private SettlementScheduler instance(StringRedisTemplate redis, Duration lockAtLeastFor) {
        Clock clock = Clock.fixed(NOW.atZone(TimeConfig.ZONE).toInstant(), TimeConfig.ZONE);
        return new SettlementScheduler(aggregationService, clock, new RedisDistributedLock(redis),
                CATCH_UP_DAYS, Duration.ofMinutes(30), lockAtLeastFor);
    }

    private SettlementScheduler instance(Duration lockAtLeastFor) {
        return instance(RedisTestSupport.template(Duration.ofSeconds(2)), lockAtLeastFor);
    }

    @Test
    void 여러_인스턴스가_동시에_실행해도_한_인스턴스만_집계한다() throws Exception {
        given(aggregationService.aggregateDaily(any())).willAnswer(inv -> {
            Thread.sleep(50); // 실행이 겹치도록 집계에 시간이 걸리게 함
            return new DailyResult(inv.getArgument(0), 0, 0, 0);
        });
        int instances = 5;
        List<SettlementScheduler> schedulers = new ArrayList<>();
        for (int i = 0; i < instances; i++) {
            schedulers.add(instance(Duration.ofMinutes(5)));
        }

        ExecutorService executor = Executors.newFixedThreadPool(instances);
        CountDownLatch ready = new CountDownLatch(instances);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        for (SettlementScheduler scheduler : schedulers) {
            futures.add(executor.submit(() -> {
                ready.countDown();
                start.await();
                scheduler.aggregateDailySettlements();
                return null;
            }));
        }
        ready.await();
        start.countDown();
        for (Future<?> future : futures) {
            future.get(10, TimeUnit.SECONDS);
        }
        executor.shutdown();

        // 3일치를 한 번씩만 (인스턴스 수만큼 반복되지 않음)
        verify(aggregationService, times(CATCH_UP_DAYS)).aggregateDaily(any());
        verify(aggregationService).aggregateDaily(LocalDate.of(2026, 11, 3));
        verify(aggregationService).aggregateDaily(LocalDate.of(2026, 11, 4));
        verify(aggregationService).aggregateDaily(LocalDate.of(2026, 11, 5));
    }

    @Test
    void 먼저_끝난_인스턴스가_있어도_최소_유지_시간_안에_늦게_깨어난_인스턴스는_다시_집계하지_않는다() {
        given(aggregationService.aggregateDaily(any())).willAnswer(inv -> new DailyResult(inv.getArgument(0), 0, 0, 0));

        instance(Duration.ofMinutes(5)).aggregateDailySettlements();
        instance(Duration.ofMinutes(5)).aggregateDailySettlements(); // 시계가 조금 늦은 인스턴스

        verify(aggregationService, times(CATCH_UP_DAYS)).aggregateDaily(any());
    }

    @Test
    void 집계가_모두_실패해도_락은_해제되어_다음_실행이_집계한다() {
        given(aggregationService.aggregateDaily(any())).willThrow(new IllegalStateException("DB 오류"));
        StringRedisTemplate redis = RedisTestSupport.template(Duration.ofSeconds(2));

        instance(redis, Duration.ZERO).aggregateDailySettlements();

        assertThat(redis.hasKey(LOCK_KEY)).isFalse();
        instance(redis, Duration.ZERO).aggregateDailySettlements();
        verify(aggregationService, times(CATCH_UP_DAYS * 2)).aggregateDaily(any());
    }

    @Test
    void Redis에_연결할_수_없으면_어느_인스턴스도_집계하지_않는다() {
        instance(RedisTestSupport.unreachableTemplate(), Duration.ZERO).aggregateDailySettlements();
        instance(RedisTestSupport.unreachableTemplate(), Duration.ZERO).aggregateDailySettlements();

        verifyNoInteractions(aggregationService);
    }

    @Test
    void Redis_응답이_타임아웃되면_집계하지_않는다() {
        SettlementScheduler scheduler = instance(RedisTestSupport.template(Duration.ofMillis(300)), Duration.ZERO);
        RedisTestSupport.pause(Duration.ofSeconds(1));

        scheduler.aggregateDailySettlements();

        verifyNoInteractions(aggregationService);
    }
}
