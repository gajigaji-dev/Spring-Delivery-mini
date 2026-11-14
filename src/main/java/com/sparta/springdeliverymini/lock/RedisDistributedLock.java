package com.sparta.springdeliverymini.lock;

import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

// Redis 단일 노드 기반 분산 락 (#41)
//
// - 획득: SET lock:{name} {token} NX PX {lockAtMostFor}
//   NX로 한 인스턴스만 성공, PX(TTL)로 인스턴스가 죽어도 락이 영원히 남지 않음
// - 해제: Lua 스크립트로 "값이 내 token일 때만" 삭제(또는 lockAtLeastFor까지 TTL 단축)
//   GET 후 DEL을 따로 보내면 그 사이 TTL이 끝나 다른 인스턴스가 잡은 락을 지울 수 있어 한 번에 처리
// - 실행 중 TTL 연장(watchdog)은 하지 않음: 일 1회 배치라 TTL을 넉넉히 잡고,
//   그래도 넘기면 경고 로그만 남김 (정산은 DB UNIQUE·행 잠금이 최종적으로 중복을 막음)
@Slf4j
@Component
public class RedisDistributedLock implements DistributedLock {

    static final String KEY_PREFIX = "lock:";

    // ARGV[1] = token, ARGV[2] = 남은 최소 유지 시간(ms)
    // 내 락이 아니면(만료 후 다른 인스턴스가 잡음) 아무것도 하지 않음
    private static final RedisScript<Long> RELEASE_SCRIPT = RedisScript.of("""
            if redis.call('GET', KEYS[1]) ~= ARGV[1] then
                return 0
            end
            local holdMillis = tonumber(ARGV[2])
            if holdMillis > 0 then
                return redis.call('PEXPIRE', KEYS[1], holdMillis)
            end
            return redis.call('DEL', KEYS[1])
            """, Long.class);

    private final StringRedisTemplate redisTemplate;

    public RedisDistributedLock(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @Override
    public boolean executeIfAcquired(String name, Duration lockAtMostFor, Duration lockAtLeastFor, Runnable task) {
        if (lockAtMostFor.isNegative() || lockAtMostFor.isZero()) {
            throw new IllegalArgumentException("lockAtMostFor는 0보다 커야 합니다: " + lockAtMostFor);
        }
        if (lockAtLeastFor.isNegative() || lockAtLeastFor.compareTo(lockAtMostFor) > 0) {
            throw new IllegalArgumentException(
                    "lockAtLeastFor는 0 이상, lockAtMostFor 이하여야 합니다: " + lockAtLeastFor + " / " + lockAtMostFor);
        }

        String key = KEY_PREFIX + name;
        // 인스턴스·실행마다 다른 값 → 해제할 때 내 락인지 구분
        String token = UUID.randomUUID().toString();

        Boolean acquired;
        try {
            acquired = redisTemplate.opsForValue().setIfAbsent(key, token, lockAtMostFor);
        } catch (DataAccessException e) {
            // 연결 실패·명령 타임아웃 — 락을 잡았는지 알 수 없으므로 실행하지 않음
            // (타임아웃이면 SET이 Redis에서 늦게 실행돼 락이 남을 수 있지만, lockAtMostFor 뒤에 만료됨)
            throw new LockUnavailableException("분산 락 상태를 확인할 수 없습니다: " + name, e);
        }
        if (!Boolean.TRUE.equals(acquired)) {
            return false;
        }

        // 경과 시간은 시스템 시계 변경에 영향받지 않는 nanoTime으로 측정 (업무 시각이 아니므로 Clock 대상 아님)
        long startedAt = System.nanoTime();
        try {
            task.run();
        } finally {
            long elapsedMillis = Duration.ofNanos(System.nanoTime() - startedAt).toMillis();
            if (elapsedMillis > lockAtMostFor.toMillis()) {
                log.warn("락 유지 시간보다 오래 실행됨 — 실행 중 락이 풀려 다른 인스턴스가 실행했을 수 있음 lock={}, elapsedMs={}, lockAtMostFor={}",
                        name, elapsedMillis, lockAtMostFor);
            }
            release(key, name, token, Math.max(lockAtLeastFor.toMillis() - elapsedMillis, 0));
        }
        return true;
    }

    // 해제 실패(Redis 장애)는 작업 결과를 바꾸지 않도록 로그만 남김 → 락은 TTL(lockAtMostFor)이 지나면 풀림
    private void release(String key, String name, String token, long holdMillis) {
        try {
            redisTemplate.execute(RELEASE_SCRIPT, List.of(key), token, String.valueOf(holdMillis));
        } catch (DataAccessException e) {
            log.warn("분산 락 해제 실패 — TTL 만료로 풀림 lock={}", name, e);
        }
    }
}
