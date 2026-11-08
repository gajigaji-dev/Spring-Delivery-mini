package com.sparta.springdeliverymini.config;

import com.sparta.springdeliverymini.TestcontainersConfiguration;
import com.sparta.springdeliverymini.entity.Role;
import com.sparta.springdeliverymini.entity.User;
import com.sparta.springdeliverymini.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;

// 시간대가 JVM·DB 세션·JPA Auditing에 모두 Asia/Seoul로 적용되는지 실제 PostgreSQL로 검증
@SpringBootTest
@Import({TestcontainersConfiguration.class, TimeZoneIntegrationTest.FixedClockConfig.class})
class TimeZoneIntegrationTest {

    // 자정 직전 시각으로 고정 → 시간대가 어긋나면(예: UTC면 14:59:59) 바로 드러남
    private static final LocalDateTime FIXED_NOW = LocalDateTime.of(2026, 11, 5, 23, 59, 59);

    @TestConfiguration(proxyBeanMethods = false)
    static class FixedClockConfig {
        // TimeConfig의 clock 대신 이 고정 Clock이 주입되도록 @Primary
        @Bean
        @Primary
        Clock fixedClock() {
            return Clock.fixed(FIXED_NOW.atZone(TimeConfig.ZONE).toInstant(), TimeConfig.ZONE);
        }
    }

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @AfterEach
    void tearDown() {
        userRepository.deleteAllInBatch();
    }

    @Test
    void JVM_기본_시간대는_Asia_Seoul이다() {
        assertThat(ZoneId.systemDefault()).isEqualTo(TimeConfig.ZONE);
    }

    @Test
    void DB_세션_시간대는_Asia_Seoul이다() {
        String dbTimeZone = jdbcTemplate.queryForObject("SHOW TIME ZONE", String.class);

        assertThat(dbTimeZone).isEqualTo("Asia/Seoul");
    }

    @Test
    void 생성_수정_시각은_Clock_기준으로_기록되고_DB에도_같은_값으로_저장된다() {
        User saved = userRepository.save(new User("customer1", "encoded-password", Role.CUSTOMER));

        assertThat(saved.getCreatedAt()).isEqualTo(FIXED_NOW);
        assertThat(saved.getUpdatedAt()).isEqualTo(FIXED_NOW);

        // JDBC 변환 과정에서 시각이 밀리지 않았는지 DB에 저장된 값을 직접 확인
        LocalDateTime stored = jdbcTemplate.queryForObject(
                "SELECT created_at FROM users WHERE id = ?", LocalDateTime.class, saved.getId());
        assertThat(stored).isEqualTo(FIXED_NOW);
    }
}
