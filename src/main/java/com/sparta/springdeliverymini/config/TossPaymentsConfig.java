package com.sparta.springdeliverymini.config;

import com.sparta.springdeliverymini.scheduler.FixedDelayTask;
import com.sparta.springdeliverymini.service.PgCancelProcessor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

// PG 결제 취소 재시도 주기 작업 (V20) — 전용 스레드(scheduler.FixedDelayTask)
// BackgroundTaskConfig에 두지 않는 이유: 그 설정만 띄우는 테스트(NotificationSenderConfigTest)에 PG 빈이 끌려 들어가지 않도록
// enabled=false면 빈을 만들지 않음(test 프로필) — 커밋 직후 1회 시도는 그대로
// 여러 인스턴스가 함께 켜져 있어도 대상 행을 SKIP LOCKED + 임대 시각으로 나눠 가지므로 분산 락이 필요 없음
@Configuration
public class TossPaymentsConfig {

    @Bean
    @ConditionalOnProperty(name = "toss.payments.cancel.enabled", havingValue = "true", matchIfMissing = true)
    FixedDelayTask pgCancelRetryTask(PgCancelProcessor processor, TossPaymentsProperties properties) {
        return new FixedDelayTask("pg-cancel-retry", properties.cancel().fixedDelay(), processor::processDue);
    }
}
