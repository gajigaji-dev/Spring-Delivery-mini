package com.sparta.springdeliverymini.config;

import com.sparta.springdeliverymini.entity.PaymentFee;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;

// application.yml의 payment.fee.rate (플랫폼 수수료율, 예: 0.05 = 5%)
// 값이 없거나 범위·자릿수가 잘못되면 바인딩 단계에서 예외 → 애플리케이션 기동 실패
@ConfigurationProperties(prefix = "payment.fee")
public record PaymentFeeProperties(BigDecimal rate) {

    public PaymentFeeProperties {
        PaymentFee.validateRate(rate);
    }
}
