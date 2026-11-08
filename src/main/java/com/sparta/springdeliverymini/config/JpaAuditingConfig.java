package com.sparta.springdeliverymini.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.auditing.DateTimeProvider;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Optional;

// JPA Auditing 활성화 (BaseEntity의 @CreatedDate, @LastModifiedDate 자동 기록)
//
// - dateTimeProviderRef: 기본값(LocalDateTime.now(), JVM 시간대 기준) 대신 Clock 빈의 시각을 기록
//   → 직접 기록하는 시각(예: Payment.paidAt)과 같은 기준을 사용하고, 테스트에서 고정 가능
// - 메인 클래스가 아닌 별도 설정 클래스에 두는 이유:
//   @WebMvcTest처럼 JPA를 띄우지 않는 테스트에서는 이 클래스가 로드되지 않아 Auditing이 JPA를 요구하지 않음
@Configuration
@EnableJpaAuditing(dateTimeProviderRef = "auditingDateTimeProvider")
public class JpaAuditingConfig {

    @Bean
    public DateTimeProvider auditingDateTimeProvider(Clock clock) {
        return () -> Optional.of(LocalDateTime.now(clock));
    }
}
