package com.sparta.springdeliverymini.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.time.ZoneId;

// 애플리케이션의 기준 시간대와 현재 시각을 한곳에서 관리
//
// - 시각 값은 LocalDateTime(시간대 정보 없음)으로 저장하므로, "몇 시"인지는 이 기준 시간대로만 해석됨
//   → 서버·DB가 어느 시간대에 있든 Asia/Seoul로 고정 (자정 기준 날짜 경계가 바뀌지 않도록)
// - 현재 시각은 LocalDateTime.now() 대신 Clock 빈에서 얻음
//   → 테스트에서 Clock.fixed(...)로 바꿔 시각을 고정할 수 있음
@Configuration
public class TimeConfig {

    // 문자열 상수는 애노테이션 속성(@Scheduled(zone = ...))에서 쓰기 위함 — 애노테이션에는 ZoneId 객체를 넣을 수 없음
    public static final String ZONE_ID = "Asia/Seoul";
    public static final ZoneId ZONE = ZoneId.of(ZONE_ID);

    @Bean
    public Clock clock() {
        return Clock.system(ZONE);
    }
}
