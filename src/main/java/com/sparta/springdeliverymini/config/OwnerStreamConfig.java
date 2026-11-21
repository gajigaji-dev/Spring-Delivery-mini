package com.sparta.springdeliverymini.config;

import com.sparta.springdeliverymini.event.OwnerOrderStreamRegistry;
import com.sparta.springdeliverymini.scheduler.FixedDelayTask;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

// 사장님 실시간 주문 알림(SSE, #76) 하트비트 주기 작업
// BackgroundTaskConfig와 분리한 이유: 그 설정은 알림 발송기 선택만 단독으로 띄워 검사하는 테스트가 있어(NotificationSenderConfigTest)
// SSE 등록부 의존성을 섞지 않음. 연결이 없으면 할 일이 없으므로 켜고 끄는 설정은 두지 않음
@Configuration
public class OwnerStreamConfig {

    @Bean
    FixedDelayTask ownerStreamHeartbeatTask(OwnerOrderStreamRegistry registry, OwnerStreamProperties properties) {
        return new FixedDelayTask("owner-stream-heartbeat", properties.heartbeat(), registry::sendHeartbeat);
    }
}
