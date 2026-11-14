package com.sparta.springdeliverymini.lock;

import com.sparta.springdeliverymini.RedisTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// Redis 분산 락을 실제 Redis로 검증 — 획득·해제·만료·예외·장애·여러 인스턴스 경합 (#41)
class RedisDistributedLockTest {

    private static final String NAME = "test-job";
    private static final String KEY = RedisDistributedLock.KEY_PREFIX + NAME;
    private static final Duration AT_MOST = Duration.ofSeconds(30);

    private final StringRedisTemplate redis = RedisTestSupport.template(Duration.ofMillis(300));
    private final RedisDistributedLock lock = new RedisDistributedLock(redis);

    @AfterEach
    void tearDown() {
        RedisTestSupport.resetAfterPause();
    }

    @Test
    void 락을_잡으면_작업을_실행하고_끝나면_해제한다() {
        AtomicInteger runs = new AtomicInteger();

        boolean executed = lock.executeIfAcquired(NAME, AT_MOST, Duration.ZERO, () -> {
            runs.incrementAndGet();
            assertThat(redis.getExpire(KEY, TimeUnit.MILLISECONDS)).isPositive(); // 실행 중에는 TTL이 걸린 락이 있음
        });

        assertThat(executed).isTrue();
        assertThat(runs).hasValue(1);
        assertThat(redis.hasKey(KEY)).isFalse();
    }

    @Test
    void 다른_인스턴스가_락을_잡고_있으면_작업을_실행하지_않는다() {
        redis.opsForValue().set(KEY, "other-instance-token", AT_MOST);
        AtomicInteger runs = new AtomicInteger();

        boolean executed = lock.executeIfAcquired(NAME, AT_MOST, Duration.ZERO, runs::incrementAndGet);

        assertThat(executed).isFalse();
        assertThat(runs).hasValue(0);
        assertThat(redis.opsForValue().get(KEY)).isEqualTo("other-instance-token"); // 남의 락은 건드리지 않음
    }

    @Test
    void 작업이_예외를_던져도_락을_해제하고_예외는_그대로_전달한다() {
        assertThatThrownBy(() -> lock.executeIfAcquired(NAME, AT_MOST, Duration.ZERO, () -> {
            throw new IllegalStateException("집계 실패");
        })).isInstanceOf(IllegalStateException.class).hasMessage("집계 실패");

        assertThat(redis.hasKey(KEY)).isFalse();
        // 바로 다시 잡을 수 있음
        assertThat(lock.executeIfAcquired(NAME, AT_MOST, Duration.ZERO, () -> { })).isTrue();
    }

    @Test
    void 최소_유지_시간_전에_끝나면_그때까지_락을_유지해_늦게_깨어난_인스턴스가_다시_실행하지_않는다() {
        lock.executeIfAcquired(NAME, AT_MOST, Duration.ofSeconds(10), () -> { });

        long ttl = redis.getExpire(KEY, TimeUnit.MILLISECONDS);
        assertThat(ttl).isPositive().isLessThanOrEqualTo(10_000); // 최대 유지 시간(30초)이 아니라 남은 최소 유지 시간으로 줄어듦

        RedisDistributedLock lateInstance = new RedisDistributedLock(RedisTestSupport.template(Duration.ofMillis(300)));
        AtomicInteger runs = new AtomicInteger();
        assertThat(lateInstance.executeIfAcquired(NAME, AT_MOST, Duration.ZERO, runs::incrementAndGet)).isFalse();
        assertThat(runs).hasValue(0);
    }

    @Test
    void 최대_유지_시간이_지나면_락이_만료되고_원래_소유자는_새_소유자의_락을_지우지_않는다() throws Exception {
        RedisDistributedLock otherInstance = new RedisDistributedLock(RedisTestSupport.template(Duration.ofMillis(300)));
        CountDownLatch otherAcquired = new CountDownLatch(1);
        CountDownLatch otherMayFinish = new CountDownLatch(1);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            // 첫 인스턴스: TTL 200ms인데 작업이 더 오래 걸림 → 실행 중 락 만료
            boolean executed = lock.executeIfAcquired(NAME, Duration.ofMillis(200), Duration.ZERO, () -> {
                awaitExpiry();
                // 만료된 사이 다른 인스턴스가 락을 잡고 실행 중
                executor.submit(() -> otherInstance.executeIfAcquired(NAME, AT_MOST, Duration.ZERO, () -> {
                    otherAcquired.countDown();
                    await(otherMayFinish);
                }));
                await(otherAcquired);
            });
            assertThat(executed).isTrue();

            // 첫 인스턴스의 해제는 token이 달라 아무것도 지우지 않음 → 새 소유자의 락이 남아 있음
            assertThat(redis.hasKey(KEY)).isTrue();
            assertThat(lock.executeIfAcquired(NAME, AT_MOST, Duration.ZERO, () -> { })).isFalse();
        } finally {
            otherMayFinish.countDown();
            executor.shutdown();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
        assertThat(redis.hasKey(KEY)).isFalse(); // 새 소유자는 끝나면 자기 락을 해제
    }

    @Test
    void 인스턴스가_해제하지_못하고_죽어도_최대_유지_시간_뒤에는_다른_인스턴스가_락을_잡는다() {
        // 죽은 인스턴스가 남긴 락 (해제 없이 TTL만 남음)
        redis.opsForValue().set(KEY, "dead-instance-token", Duration.ofMillis(200));
        assertThat(lock.executeIfAcquired(NAME, AT_MOST, Duration.ZERO, () -> { })).isFalse();

        awaitExpiry();

        assertThat(lock.executeIfAcquired(NAME, AT_MOST, Duration.ZERO, () -> { })).isTrue();
    }

    @Test
    void 여러_인스턴스가_동시에_실행해도_작업은_한_번만_실행된다() throws Exception {
        int instances = 8;
        AtomicInteger runs = new AtomicInteger();
        ExecutorService executor = Executors.newFixedThreadPool(instances);
        CountDownLatch ready = new CountDownLatch(instances);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> futures = new ArrayList<>();
        for (int i = 0; i < instances; i++) {
            // 인스턴스마다 별도 Redis 연결
            RedisDistributedLock instanceLock = new RedisDistributedLock(RedisTestSupport.template(Duration.ofSeconds(2)));
            futures.add(executor.submit(() -> {
                ready.countDown();
                start.await();
                return instanceLock.executeIfAcquired(NAME, AT_MOST, Duration.ofSeconds(5), () -> {
                    runs.incrementAndGet();
                    sleep(100);
                });
            }));
        }
        ready.await();
        start.countDown();

        int executed = 0;
        for (Future<Boolean> future : futures) {
            if (future.get(10, TimeUnit.SECONDS)) {
                executed++;
            }
        }
        executor.shutdown();

        assertThat(executed).isEqualTo(1);
        assertThat(runs).hasValue(1);
    }

    @Test
    void Redis에_연결할_수_없으면_작업을_실행하지_않고_LockUnavailableException() {
        RedisDistributedLock downLock = new RedisDistributedLock(RedisTestSupport.unreachableTemplate());
        AtomicInteger runs = new AtomicInteger();

        assertThatThrownBy(() -> downLock.executeIfAcquired(NAME, AT_MOST, Duration.ZERO, runs::incrementAndGet))
                .isInstanceOf(LockUnavailableException.class);
        assertThat(runs).hasValue(0);
    }

    @Test
    void Redis_응답이_타임아웃되면_작업을_실행하지_않고_LockUnavailableException() {
        AtomicInteger runs = new AtomicInteger();
        RedisTestSupport.pause(Duration.ofSeconds(1));

        assertThatThrownBy(() -> lock.executeIfAcquired(NAME, AT_MOST, Duration.ZERO, runs::incrementAndGet))
                .isInstanceOf(LockUnavailableException.class);
        assertThat(runs).hasValue(0);
    }

    @Test
    void 작업이_끝난_뒤_해제가_실패해도_작업_결과는_그대로다() {
        AtomicInteger runs = new AtomicInteger();

        // 작업 중 Redis가 멈춤 → 해제 명령은 타임아웃 (락은 TTL로 풀림)
        boolean executed = lock.executeIfAcquired(NAME, AT_MOST, Duration.ZERO, () -> {
            runs.incrementAndGet();
            RedisTestSupport.pause(Duration.ofSeconds(1));
        });

        assertThat(executed).isTrue();
        assertThat(runs).hasValue(1);
    }

    @Test
    void 잘못된_유지_시간은_거부한다() {
        assertThatThrownBy(() -> lock.executeIfAcquired(NAME, Duration.ZERO, Duration.ZERO, () -> { }))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> lock.executeIfAcquired(NAME, Duration.ofSeconds(1), Duration.ofSeconds(2), () -> { }))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // 200ms TTL이 확실히 지나도록 대기 (Redis 만료는 밀리초 단위로 정확)
    private static void awaitExpiry() {
        sleep(400);
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
