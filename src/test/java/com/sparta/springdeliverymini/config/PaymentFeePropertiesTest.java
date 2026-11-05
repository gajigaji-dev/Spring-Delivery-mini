package com.sparta.springdeliverymini.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

// payment.fee.rate 설정이 BigDecimal로 정확히 바인딩되고, 잘못된 값이면 기동이 실패하는지 검증
class PaymentFeePropertiesTest {

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(PaymentFeeProperties.class)
    static class Config {
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(Config.class);

    @Test
    void 수수료율을_BigDecimal로_바인딩한다() {
        runner.withPropertyValues("payment.fee.rate=0.05")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(PaymentFeeProperties.class).rate())
                            .isEqualByComparingTo("0.05");
                });
    }

    @Test
    void 수수료율이_1을_넘으면_기동에_실패한다() {
        runner.withPropertyValues("payment.fee.rate=1.5")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void 수수료율이_없으면_기동에_실패한다() {
        runner.run(context -> assertThat(context).hasFailed());
    }
}
