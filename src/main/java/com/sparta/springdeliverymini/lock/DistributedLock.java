package com.sparta.springdeliverymini.lock;

import java.time.Duration;

// 여러 애플리케이션 인스턴스 사이의 실행 락 (구현: RedisDistributedLock, #41)
// 인터페이스로 둔 이유: 스케줄러 단위 테스트에서 Redis 없이 "락 획득·실패·장애"를 바꿔 끼우기 위함
public interface DistributedLock {

    // 락을 잡으면 task를 실행하고 true, 다른 인스턴스가 잡고 있으면 실행하지 않고 false
    // - lockAtMostFor: 락 최대 유지 시간(TTL). 인스턴스가 죽어 해제하지 못해도 이 시간이 지나면 풀림
    //   → 작업 최대 실행 시간보다 넉넉하게 잡아야 함 (짧으면 실행 중에 풀려 다른 인스턴스가 또 실행)
    // - lockAtLeastFor: 작업이 빨리 끝나도 이 시간까지는 락을 유지
    //   → 인스턴스마다 시계가 조금씩 달라 늦게 깨어난 인스턴스가 "이미 끝난" 작업을 다시 실행하는 것을 막음
    // - 작업이 예외를 던져도 락은 해제(또는 lockAtLeastFor까지 유지)한 뒤 예외를 그대로 던짐
    // - Redis 장애·타임아웃으로 락 상태를 알 수 없으면 task를 실행하지 않고 LockUnavailableException
    boolean executeIfAcquired(String name, Duration lockAtMostFor, Duration lockAtLeastFor, Runnable task);
}
