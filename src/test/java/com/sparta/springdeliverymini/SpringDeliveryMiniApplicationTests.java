package com.sparta.springdeliverymini;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

// 전체 애플리케이션 컨텍스트가 뜨는지 확인
// DB는 Testcontainers PostgreSQL 사용 → Flyway 마이그레이션 + ddl-auto: validate까지 함께 검증
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class SpringDeliveryMiniApplicationTests {

    @Test
    void contextLoads() {
    }

}
