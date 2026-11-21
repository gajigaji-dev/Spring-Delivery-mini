package com.sparta.springdeliverymini.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

// application.yml의 owner-stream.* — 사장님 실시간 주문 알림(SSE, #76) 설정 (event.OwnerOrderStreamRegistry)
// - timeout: 연결 하나의 최대 유지 시간. Access Token 만료(15분)보다 짧게 두어, 끊긴 뒤 다시 연결할 때
//   새 토큰과 DB 기준 역할·매장 소유권을 다시 확인하게 함 (오래 열린 연결이 권한 변경을 계속 무시하지 않도록)
// - heartbeat: 주석 한 줄(:ping)을 보내는 간격. 프록시·로드밸런서의 유휴 연결 끊김을 막고, 이미 끊긴 연결을 찾아 정리
// - max-connections-per-user: 한 사용자의 동시 연결 상한(탭 여러 개). 넘으면 가장 오래된 연결을 닫음
//   (새로고침 직후 이전 탭 연결이 아직 정리되지 않은 경우가 흔해, 새 연결을 거절하지 않고 오래된 쪽을 정리)
// - send-queue-capacity: 전송 대기열 크기. 가득 차면 그 알림은 버림 → 화면은 재연결·주기 조회로 따라잡음
@ConfigurationProperties(prefix = "owner-stream")
public record OwnerStreamProperties(Duration timeout, Duration heartbeat, int maxConnectionsPerUser,
                                    int sendQueueCapacity) {

    public OwnerStreamProperties {
        requirePositive("timeout", timeout);
        requirePositive("heartbeat", heartbeat);
        if (heartbeat.compareTo(timeout) >= 0) {
            throw new IllegalArgumentException("owner-stream.heartbeat는 timeout보다 짧아야 합니다.");
        }
        if (maxConnectionsPerUser < 1 || sendQueueCapacity < 1) {
            throw new IllegalArgumentException(
                    "owner-stream.max-connections-per-user, send-queue-capacity는 1 이상이어야 합니다.");
        }
    }

    private static void requirePositive(String name, Duration value) {
        if (value == null || value.isNegative() || value.isZero()) {
            throw new IllegalArgumentException("owner-stream." + name + "는 0보다 커야 합니다: " + value);
        }
    }
}
