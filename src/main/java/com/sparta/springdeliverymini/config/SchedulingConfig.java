package com.sparta.springdeliverymini.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

// @Scheduled 작업(scheduler.SettlementScheduler) 활성화
// settlement.scheduler.enabled=false면 스케줄 실행을 끔 (예: 여러 서버 중 한 대에서만 돌리고 싶을 때)
// 메인 클래스가 아닌 별도 설정 클래스에 두는 이유: 켜고 끄는 조건(@ConditionalOnProperty)을 함께 둘 수 있고,
// @WebMvcTest처럼 일부만 띄우는 테스트에서는 로드되지 않음 (JpaAuditingConfig와 같은 방식)
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "settlement.scheduler.enabled", havingValue = "true", matchIfMissing = true)
public class SchedulingConfig {
}
