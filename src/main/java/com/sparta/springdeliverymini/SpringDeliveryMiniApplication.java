package com.sparta.springdeliverymini;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

@SpringBootApplication
// JPA Auditing 활성화
// @MappedSuperclass로 공통 필드를 상속시키고, @EnableJpaAuditing으로 자동 기록 기능을 활성
@EnableJpaAuditing
public class SpringDeliveryMiniApplication {

    public static void main(String[] args) {
        SpringApplication.run(SpringDeliveryMiniApplication.class, args);
    }

}
