package com.sparta.springdeliverymini.dto;

import com.sparta.springdeliverymini.entity.Store;

import java.math.BigDecimal;

// 매장 수수료율 응답 (관리자 전용)
// 수수료율은 플랫폼 내부 정보 → 누구나 조회할 수 있는 StoreResponse에는 넣지 않고 관리자 응답으로만 제공
public record AdminStoreFeeRateResponse(
        Long storeId,
        String storeName,
        BigDecimal feeRate,            // 매장 수수료율 (설정하지 않았으면 null)
        BigDecimal effectiveFeeRate,   // 다음 결제부터 실제로 적용되는 수수료율
        boolean defaultFeeRateApplied  // true면 공통 수수료율(application.yml payment.fee.rate)을 사용 중
) {

    public static AdminStoreFeeRateResponse of(Store store, BigDecimal defaultRate) {
        return new AdminStoreFeeRateResponse(
                store.getId(),
                store.getName(),
                store.getFeeRate(),
                store.effectiveFeeRate(defaultRate),
                store.getFeeRate() == null
        );
    }
}
