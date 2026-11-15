package com.sparta.springdeliverymini.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

// application.yml의 payment.idempotency — 결제 멱등성 키 보관 시간 (service.PaymentIdempotencyService, #41)
// - processing-ttl: 처리 중 기록의 유지 시간. 서버가 처리 도중 죽어도 이 시간이 지나면 같은 키로 다시 시도 가능
//   → 결제 1건 처리 시간(주문 행 잠금 대기 포함)보다 넉넉해야 함. 짧으면 처리 중에 풀려 재시도가 DB 409로 끝남
// - completed-ttl: 완료 응답 보관 시간. 이 시간 안의 재시도는 처음 응답을 그대로 돌려받음
//   → 지나면 키가 사라져 재시도는 DB 검사(이미 결제된 주문 409)로 처리
// 값이 없거나 0 이하면 바인딩 단계에서 예외 → 애플리케이션 기동 실패
@ConfigurationProperties(prefix = "payment.idempotency")
public record PaymentIdempotencyProperties(Duration processingTtl, Duration completedTtl) {

    public PaymentIdempotencyProperties {
        requirePositive("processing-ttl", processingTtl);
        requirePositive("completed-ttl", completedTtl);
        if (completedTtl.compareTo(processingTtl) < 0) {
            throw new IllegalArgumentException("payment.idempotency.completed-ttl은 processing-ttl 이상이어야 합니다.");
        }
    }

    private static void requirePositive(String name, Duration value) {
        if (value == null || value.isNegative() || value.isZero()) {
            throw new IllegalArgumentException("payment.idempotency." + name + "는 0보다 커야 합니다: " + value);
        }
    }
}
