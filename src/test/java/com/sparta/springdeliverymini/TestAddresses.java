package com.sparta.springdeliverymini;

import com.sparta.springdeliverymini.entity.DeliveryAddress;

// 테스트용 배송 주소 (#77) — 주소 자체를 검증하지 않는 테스트에서 공통으로 사용
public final class TestAddresses {

    private TestAddresses() {
    }

    public static DeliveryAddress seoul() {
        return new DeliveryAddress("06236", "서울 강남구 테헤란로 152", "101호");
    }
}
