package com.sparta.springdeliverymini;

import com.sparta.springdeliverymini.config.TimeConfig;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import java.util.TimeZone;

// JPA Auditing 설정은 config.JpaAuditingConfig, 시간대·Clock 설정은 config.TimeConfig
@SpringBootApplication
public class SpringDeliveryMiniApplication {

    public static void main(String[] args) {
        // 애플리케이션이 뜨기 전에 JVM 기본 시간대를 Asia/Seoul로 고정
        // PostgreSQL JDBC 드라이버는 연결 시 JVM 기본 시간대를 DB 세션 시간대로 사용하므로,
        // DB의 CURRENT_TIMESTAMP(예: menus.updated_at 기본값)도 같은 기준이 됨
        // (테스트는 main을 거치지 않으므로 build.gradle의 test 설정에서 같은 값을 지정)
        TimeZone.setDefault(TimeZone.getTimeZone(TimeConfig.ZONE));
        SpringApplication.run(SpringDeliveryMiniApplication.class, args);
    }

}
